package com.chill.familyvlog.subtitle

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.chill.familyvlog.output.CachePrivateOutputStore
import com.chill.familyvlog.output.ContentResolverPendingMediaStore
import com.chill.familyvlog.output.ExportCoordinator
import com.chill.familyvlog.output.MediaExtractorOutputInspector
import com.chill.familyvlog.output.MediaStorePublisher
import com.chill.familyvlog.output.PublicationReceipt
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.render.ActiveExportRegistry
import com.chill.familyvlog.render.ExportHandle
import com.chill.familyvlog.render.ExportHandleFactory
import com.chill.familyvlog.render.RenderClip
import com.chill.familyvlog.render.RenderSpec
import com.chill.familyvlog.render.Renderer
import com.chill.familyvlog.render.awaitExport
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@UnstableApi
class Media3SubtitleExporter(
    context: Context,
) : SubtitleExporter {
    private val applicationContext = context.applicationContext

    override suspend fun export(
        source: SubtitleSource,
        assDocument: String,
        onSaving: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt {
        require(source.hasAudio && assDocument.isNotBlank())
        val fontBytes = loadSubtitleFont {
            withContext(Dispatchers.IO) {
                applicationContext.assets.open(SUBTITLE_FONT_ASSET).use { it.readBytes() }
            }
        }
        val renderer = SubtitleMedia3Renderer(
            context = applicationContext,
            source = source,
            assDocument = assDocument.toByteArray(UTF_8),
            fontBytes = fontBytes,
        )
        val coordinator = ExportCoordinator(
            renderer = renderer,
            inspector = MediaExtractorOutputInspector(),
            publisher = MediaStorePublisher(
                pendingMediaStore = ContentResolverPendingMediaStore(applicationContext.contentResolver),
                ioDispatcher = Dispatchers.IO,
                mainDispatcher = Dispatchers.Main.immediate,
                displayName = { "family-vlog-subtitled-${UUID.randomUUID()}.mp4" },
            ),
            privateOutputStore = CachePrivateOutputStore(applicationContext.cacheDir),
            onSaving = onSaving,
            onCommitted = onCommitted,
        )
        val spec = RenderSpec(
            clips = listOf(RenderClip(source.uri, 0, source.durationUs, hasAudio = true)),
            expectsAudio = true,
            canvasAspectRatio = source.videoWidth.toFloat() / source.videoHeight,
        )
        return try {
            coordinator.export(spec)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: PipelineException) {
            throw failure.toSubtitleException()
        } catch (failure: LinkageError) {
            throw SubtitleException(SubtitlePhase.RENDERING, SubtitleFailureCode.RENDER_FAILED, failure)
        } catch (failure: Exception) {
            throw SubtitleException(SubtitlePhase.RENDERING, SubtitleFailureCode.RENDER_FAILED, failure)
        }
    }
}

@UnstableApi
private class SubtitleMedia3Renderer(
    context: Context,
    private val source: SubtitleSource,
    private val assDocument: ByteArray,
    private val fontBytes: ByteArray,
) : Renderer {
    private val applicationContext = context.applicationContext
    private val mainLooper = Looper.getMainLooper()
    private val activeExports = ActiveExportRegistry<ExportHandle>()

    override suspend fun render(spec: RenderSpec, output: File): ExportResult {
        require(
            spec.clips == listOf(RenderClip(source.uri, 0, source.durationUs, hasAudio = true)) &&
                spec.expectsAudio,
        )
        return withSubtitleRenderingResource(
            create = {
                AssCanvasOverlay(
                    assDocument,
                    fontBytes,
                    source.videoWidth,
                    source.videoHeight,
                )
            },
            release = AssCanvasOverlay::release,
            use = { overlay ->
                withContext(Dispatchers.Main.immediate) {
                    val composition = buildSubtitleComposition(source, OverlayEffect(listOf(overlay)))
                    awaitSubtitleExport(
                        activeExports = activeExports,
                        handleFactory = ExportHandleFactory { onCompleted, onError ->
                            lateinit var listener: Transformer.Listener
                            val transformer = Transformer.Builder(applicationContext)
                                .setLooper(mainLooper)
                                .setVideoMimeType(MimeTypes.VIDEO_H264)
                                .addListener(object : Transformer.Listener {
                                    override fun onCompleted(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                    ) {
                                        onCompleted(exportResult)
                                    }

                                    override fun onError(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException,
                                    ) {
                                        onError(exportException)
                                    }
                                }.also { listener = it })
                                .build()
                            object : ExportHandle {
                                override fun start() = transformer.start(composition, output.absolutePath)
                                override fun cancel() = transformer.cancel()
                                override fun removeListener() = transformer.removeListener(listener)
                            }
                        },
                    )
                }
            },
        )
    }
}

internal suspend fun loadSubtitleFont(read: suspend () -> ByteArray): ByteArray = try {
    read()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    throw SubtitleException(
        SubtitlePhase.RENDERING,
        SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
        failure,
    )
}

internal suspend fun <T : Any, R> withSubtitleRenderingResource(
    create: () -> T,
    release: (T) -> Unit,
    use: suspend (T) -> R,
): R {
    var resource: T? = null
    try {
        withContext(Dispatchers.Default) {
            resource = create()
        }
        currentCoroutineContext().ensureActive()
        return use(checkNotNull(resource))
    } finally {
        withContext(NonCancellable + Dispatchers.Default) {
            resource?.let(release)
        }
    }
}

@UnstableApi
internal suspend fun awaitSubtitleExport(
    activeExports: ActiveExportRegistry<ExportHandle>,
    handleFactory: ExportHandleFactory,
): ExportResult = awaitExport(activeExports, handleFactory)

@UnstableApi
internal fun buildSubtitleComposition(source: SubtitleSource, subtitleEffect: GlEffect): Composition {
    require(source.durationUs > 0 && source.videoWidth > 0 && source.videoHeight > 0 && source.hasAudio)
    val item = EditedMediaItem.Builder(MediaItem.fromUri(source.uri)).build()
    return Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item)))
        .setEffects(Effects(emptyList(), listOf(subtitleEffect)))
        .setTransmuxAudio(true)
        .build()
}

private fun PipelineException.toSubtitleException(): SubtitleException {
    val subtitlePhase = if (phase == RunPhase.SAVING) SubtitlePhase.SAVING else SubtitlePhase.RENDERING
    val subtitleCode = when (code) {
        RunFailureCode.PRIVATE_STORAGE_FAILED -> SubtitleFailureCode.PRIVATE_STORAGE_FAILED
        RunFailureCode.OUTPUT_INSPECTION_FAILED -> SubtitleFailureCode.OUTPUT_INSPECTION_FAILED
        RunFailureCode.PUBLISH_FAILED -> SubtitleFailureCode.PUBLISH_FAILED
        else -> SubtitleFailureCode.RENDER_FAILED
    }
    return SubtitleException(subtitlePhase, subtitleCode, this)
}
