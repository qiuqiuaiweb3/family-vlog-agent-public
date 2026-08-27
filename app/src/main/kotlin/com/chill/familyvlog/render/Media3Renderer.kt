package com.chill.familyvlog.render

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@UnstableApi
interface Renderer {
    suspend fun render(spec: RenderSpec, output: File): ExportResult
}

@UnstableApi
class Media3Renderer(context: Context) : Renderer {
    private val applicationContext = context.applicationContext
    private val mainLooper = Looper.getMainLooper()
    private val activeExports = ActiveExportRegistry<ExportHandle>()

    override suspend fun render(spec: RenderSpec, output: File): ExportResult =
        withContext(Dispatchers.Main.immediate) {
            val composition = try {
                buildComposition(spec)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw renderFailure(failure)
            }
            exportOnMain(spec, composition, output)
        }

    private suspend fun exportOnMain(
        spec: RenderSpec,
        composition: Composition,
        output: File,
    ): ExportResult {
        check(Looper.myLooper() == mainLooper)
        return awaitExport(
            activeExports = activeExports,
            handleFactory = ExportHandleFactory { onCompleted, onError ->
                lateinit var listener: Transformer.Listener
                val transformer = Transformer.Builder(applicationContext)
                    .setLooper(mainLooper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .apply {
                        if (spec.expectsAudio) setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            check(Looper.myLooper() == mainLooper)
                            onCompleted(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            check(Looper.myLooper() == mainLooper)
                            onError(exportException)
                        }
                    }.also { listener = it })
                    .build()
                object : ExportHandle {
                    override fun start() {
                        transformer.start(composition, output.absolutePath)
                    }

                    override fun cancel() {
                        transformer.cancel()
                    }

                    override fun removeListener() {
                        transformer.removeListener(listener)
                    }
                }
            },
        )
    }
}

internal interface ExportHandle {
    fun start()
    fun cancel()
    fun removeListener()
}

@UnstableApi
internal fun interface ExportHandleFactory {
    fun create(
        onCompleted: (ExportResult) -> Unit,
        onError: (ExportException) -> Unit,
    ): ExportHandle
}

@UnstableApi
internal suspend fun awaitExport(
    activeExports: ActiveExportRegistry<ExportHandle>,
    handleFactory: ExportHandleFactory,
): ExportResult {
    val operationId = Any()
    try {
        return suspendCancellableCoroutine { continuation ->
            val completed: (ExportResult) -> Unit = completed@ { exportResult ->
                if (!releaseExport(activeExports, operationId) || !continuation.isActive) return@completed
                continuation.resume(exportResult)
            }
            val errored: (ExportException) -> Unit = errored@ { exportException ->
                if (!releaseExport(activeExports, operationId) || !continuation.isActive) return@errored
                continuation.resumeWithException(renderFailure(exportException))
            }
            val handle = try {
                handleFactory.create(completed, errored)
            } catch (failure: Exception) {
                continuation.resumeWithException(renderFailure(failure))
                return@suspendCancellableCoroutine
            }
            if (!activeExports.tryAcquire(operationId, handle)) {
                handle.removeListenerSafely()
                continuation.resumeWithException(renderFailure())
                return@suspendCancellableCoroutine
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                handle.start()
            } catch (failure: Exception) {
                val released = cancelExport(activeExports, operationId)
                if (released && continuation.isActive) {
                    continuation.resumeWithException(renderFailure(failure))
                }
            }
        }
    } finally {
        cancelExport(activeExports, operationId)
    }
}

private fun releaseExport(activeExports: ActiveExportRegistry<ExportHandle>, operationId: Any): Boolean {
    val current = activeExports.take(operationId) ?: return false
    current.removeListenerSafely()
    return true
}

private fun cancelExport(activeExports: ActiveExportRegistry<ExportHandle>, operationId: Any): Boolean {
    val current = activeExports.take(operationId) ?: return false
    try {
        current.cancel()
    } catch (_: Exception) {
    } finally {
        current.removeListenerSafely()
    }
    return true
}

private fun ExportHandle.removeListenerSafely() {
    try {
        removeListener()
    } catch (_: Exception) {
    }
}

internal class ActiveExportRegistry<T> {
    private var active: Entry<T>? = null

    fun tryAcquire(id: Any, value: T): Boolean {
        if (active != null) return false
        active = Entry(id, value)
        return true
    }

    fun take(id: Any): T? {
        val current = active ?: return null
        if (current.id !== id) return null
        active = null
        return current.value
    }

    private data class Entry<T>(val id: Any, val value: T)
}

@UnstableApi
internal fun buildComposition(spec: RenderSpec): Composition {
    if (
        spec.clips.isEmpty() ||
        spec.expectsAudio != spec.clips.any(RenderClip::hasAudio) ||
        !spec.canvasAspectRatio.isFinite() ||
        spec.canvasAspectRatio <= 0f ||
        spec.clips.any { it.startUs < 0 || it.endUs <= it.startUs }
    ) {
        throw renderFailure()
    }

    val items = spec.clips.map { clip ->
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.startUs)
            .setEndPositionUs(clip.endUs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(clipping)
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(!clip.hasAudio)
            .setEffects(buildAudioEffects(clip.hasAudio))
            .build()
    }
    val sequence = if (spec.expectsAudio) {
        EditedMediaItemSequence.withAudioAndVideoFrom(items)
    } else {
        EditedMediaItemSequence.withVideoFrom(items)
    }
    val presentation = Presentation.createForAspectRatio(
        spec.canvasAspectRatio,
        Presentation.LAYOUT_SCALE_TO_FIT,
    )
    return Composition.Builder(sequence)
        .setEffects(Effects(emptyList(), listOf(presentation)))
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
}

@UnstableApi
internal fun buildAudioEffects(
    hasAudio: Boolean,
    processorFactory: (List<ChannelMixingMatrix>) -> AudioProcessor = ::createChannelMixer,
): Effects {
    if (!hasAudio) return Effects.EMPTY
    val matrices = (1..6).map { inputChannels ->
        ChannelMixingMatrix.createForConstantPower(inputChannels, 2)
    }
    return Effects(listOf(processorFactory(matrices)), emptyList())
}

@UnstableApi
private fun createChannelMixer(matrices: List<ChannelMixingMatrix>): AudioProcessor {
    val processor = ChannelMixingAudioProcessor()
    matrices.forEach(processor::putChannelMixingMatrix)
    return processor
}

private fun renderFailure(cause: Throwable? = null): PipelineException =
    PipelineException(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED).also { failure ->
        if (cause != null) failure.initCause(cause)
    }
