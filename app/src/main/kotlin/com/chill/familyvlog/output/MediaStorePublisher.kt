package com.chill.familyvlog.output

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PublicationReceipt(val uri: Uri)

interface Publisher {
    suspend fun publish(
        privateFile: File,
        beforeCommit: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt
}

internal interface PendingMediaStore {
    fun insertPending(displayName: String): Uri?
    fun openOutput(uri: Uri): OutputStream?
    fun commit(uri: Uri): Int
    fun deleteExact(uri: Uri): Int
}

internal class ContentResolverPendingMediaStore(
    private val resolver: ContentResolver,
) : PendingMediaStore {
    override fun insertPending(displayName: String): Uri? = resolver.insert(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )

    override fun openOutput(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "w")

    override fun commit(uri: Uri): Int = resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    )

    override fun deleteExact(uri: Uri): Int = resolver.delete(uri, null, null)
}

class MediaStorePublisher internal constructor(
    private val pendingMediaStore: PendingMediaStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val displayName: () -> String,
) : Publisher {
    constructor(context: Context) : this(
        pendingMediaStore = ContentResolverPendingMediaStore(context.contentResolver),
        ioDispatcher = Dispatchers.IO,
        mainDispatcher = Dispatchers.Main.immediate,
        displayName = { "family-vlog-${UUID.randomUUID()}.mp4" },
    )

    override suspend fun publish(
        privateFile: File,
        beforeCommit: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt {
        val callerJob = currentCoroutineContext()[Job] ?: throw publishFailure()
        return withContext(ioDispatcher) {
            publishOnIo(privateFile, beforeCommit, onCommitted, callerJob)
        }
    }

    private suspend fun publishOnIo(
        privateFile: File,
        beforeCommit: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
        callerJob: Job,
    ): PublicationReceipt {
        val uri = try {
            pendingMediaStore.insertPending(displayName())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            callerJob.ensureActive()
            throw publishFailure()
        } ?: run {
            callerJob.ensureActive()
            throw publishFailure()
        }

        try {
            callerJob.ensureActive()
            val output = pendingMediaStore.openOutput(uri) ?: throw IllegalStateException()
            output.use { target ->
                FileInputStream(privateFile).use { source ->
                    copyCancellable(source, target, callerJob)
                }
            }
            beforeCommit()
            callerJob.ensureActive()
        } catch (failure: Exception) {
            failBeforeCommit(uri, callerJob, failure)
        }

        return commitTail(uri, callerJob, onCommitted)
    }

    private suspend fun failBeforeCommit(
        uri: Uri,
        callerJob: Job,
        failure: Exception,
    ): Nothing = withContext(NonCancellable) {
        if (!deletePending(uri)) {
            val publishFailure = publishFailure()
            publishFailure.addSuppressed(PendingCleanupFailure)
            if (failure.isPrivateStorageFailure()) publishFailure.addSuppressed(failure)
            throw publishFailure
        }
        if (failure.isPrivateStorageFailure()) throw failure
        callerJob.ensureActive()
        if (failure is CancellationException) throw failure
        throw publishFailure()
    }

    private suspend fun commitTail(
        uri: Uri,
        callerJob: Job,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt = withContext(NonCancellable) {
        val committed = try {
            pendingMediaStore.commit(uri) == 1
        } catch (_: Exception) {
            false
        }
        if (committed) {
            val receipt = PublicationReceipt(uri)
            withContext(mainDispatcher) { onCommitted(receipt) }
            return@withContext receipt
        }

        if (!deletePending(uri)) throw publishFailure().also {
            it.addSuppressed(PendingCleanupFailure)
        }
        callerJob.ensureActive()
        throw publishFailure()
    }

    private fun deletePending(uri: Uri): Boolean = try {
        pendingMediaStore.deleteExact(uri) == 1
    } catch (_: Exception) {
        false
    }

    private fun copyCancellable(
        source: FileInputStream,
        target: OutputStream,
        callerJob: Job,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            callerJob.ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            callerJob.ensureActive()
            target.write(buffer, 0, count)
        }
        callerJob.ensureActive()
        target.flush()
    }
}

private fun Exception.isPrivateStorageFailure(): Boolean =
    this is PipelineException &&
        phase == RunPhase.SAVING &&
        code == RunFailureCode.PRIVATE_STORAGE_FAILED

private fun publishFailure() = PipelineException(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED)

internal data object PendingCleanupFailure : RuntimeException()
