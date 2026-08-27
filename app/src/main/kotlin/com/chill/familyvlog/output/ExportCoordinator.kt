package com.chill.familyvlog.output

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.render.RenderSpec
import com.chill.familyvlog.render.Renderer
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface PrivateOutputStore {
    fun create(): File
    fun deleteExact(file: File): Boolean
}

internal class CachePrivateOutputStore(
    private val cacheDir: File,
) : PrivateOutputStore {
    override fun create(): File {
        val output = File(cacheDir, "family-vlog-export-${UUID.randomUUID()}.mp4")
        check(!output.exists())
        return output
    }

    override fun deleteExact(file: File): Boolean = !file.exists() || file.delete()
}

@UnstableApi
class ExportCoordinator internal constructor(
    private val renderer: Renderer,
    private val inspector: OutputInspector,
    private val publisher: Publisher,
    private val privateOutputStore: PrivateOutputStore,
    private val onSaving: () -> Unit,
    private val onCommitted: (PublicationReceipt) -> Unit,
) {
    constructor(
        context: Context,
        renderer: Renderer,
        onSaving: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ) : this(
        renderer = renderer,
        inspector = MediaExtractorOutputInspector(),
        publisher = MediaStorePublisher(context),
        privateOutputStore = CachePrivateOutputStore(context.cacheDir),
        onSaving = onSaving,
        onCommitted = onCommitted,
    )

    suspend fun export(spec: RenderSpec): PublicationReceipt {
        val privateFile = try {
            privateOutputStore.create()
        } catch (_: Exception) {
            throw privateStorageFailure(RunPhase.RENDERING)
        }
        var savingStarted = false
        var privateDeleted = false
        var primaryFailure: Throwable? = null
        try {
            render(spec, privateFile)
            inspect(spec, privateFile)
            savingStarted = true
            reportSaving()
            var beforeCommitCalled = false
            return publish(
                privateFile = privateFile,
                beforeCommit = {
                    if (beforeCommitCalled) throw publishFailure()
                    beforeCommitCalled = true
                    if (!deletePrivate(privateFile)) throw privateStorageFailure(RunPhase.SAVING)
                    privateDeleted = true
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!privateDeleted && !deletePrivate(privateFile)) {
                val cleanupFailure = privateStorageFailure(
                    if (savingStarted) RunPhase.SAVING else RunPhase.RENDERING,
                )
                when {
                    primaryFailure.hasPendingCleanupFailure() -> {
                        if (primaryFailure?.suppressed?.none { it.isMatchingPrivateFailure(cleanupFailure.phase) } == true) {
                            primaryFailure?.addSuppressed(cleanupFailure)
                        }
                    }
                    primaryFailure.isMatchingPrivateFailure(cleanupFailure.phase) -> Unit
                    else -> {
                        primaryFailure?.let(cleanupFailure::addSuppressed)
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    private suspend fun render(spec: RenderSpec, privateFile: File) {
        try {
            renderer.render(spec, privateFile)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw PipelineException(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED)
        }
    }

    private suspend fun inspect(spec: RenderSpec, privateFile: File) {
        val result = try {
            inspector.inspect(privateFile)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw PipelineException(RunPhase.RENDERING, RunFailureCode.OUTPUT_INSPECTION_FAILED)
        }
        currentCoroutineContext().ensureActive()
        if (!result.nonEmpty || !result.parseable || !result.hasVideo || (spec.expectsAudio && !result.hasAudio)) {
            throw PipelineException(RunPhase.RENDERING, RunFailureCode.OUTPUT_INSPECTION_FAILED)
        }
    }

    private fun reportSaving() {
        try {
            onSaving()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw publishFailure()
        }
    }

    private suspend fun publish(
        privateFile: File,
        beforeCommit: () -> Unit,
    ): PublicationReceipt = try {
        publisher.publish(privateFile, beforeCommit, onCommitted)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: PipelineException) {
        throw failure
    } catch (_: Exception) {
        throw publishFailure()
    }

    private fun deletePrivate(file: File): Boolean = try {
        privateOutputStore.deleteExact(file)
    } catch (_: Exception) {
        false
    }
}

private fun Throwable?.hasPendingCleanupFailure(): Boolean =
    this is PipelineException &&
        phase == RunPhase.SAVING &&
        code == RunFailureCode.PUBLISH_FAILED &&
        suppressed.any { it === PendingCleanupFailure }

private fun Throwable?.isMatchingPrivateFailure(phase: RunPhase): Boolean =
    this is PipelineException && this.phase == phase && code == RunFailureCode.PRIVATE_STORAGE_FAILED

private fun privateStorageFailure(phase: RunPhase) =
    PipelineException(phase, RunFailureCode.PRIVATE_STORAGE_FAILED)

private fun publishFailure() = PipelineException(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED)
