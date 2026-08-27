package com.chill.familyvlog.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.chill.familyvlog.ai.SourceBytesReadResult
import com.chill.familyvlog.ai.readBoundedSource
import com.chill.familyvlog.media.LogicalMediaTrackSelection
import com.chill.familyvlog.media.integerValueOrNull
import com.chill.familyvlog.media.selectAndroidMediaTracks
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@UnstableApi
class AndroidAnalysisMediaAdapter(context: Context) : AnalysisMediaAdapter {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver

    override suspend fun syncSampleStarts(uri: Uri): List<Long> = withContext(Dispatchers.IO) {
        withExtractor(uri) { extractor ->
            val videoTrack = extractor.mediaTrackSelection().singleVideoTrack?.index
                ?: throw IllegalArgumentException("single video track required")
            extractor.selectTrack(videoTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            buildList {
                while (extractor.sampleTime >= 0L) {
                    coroutineContext.ensureActive()
                    if (
                        extractor.sampleTrackIndex == videoTrack &&
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    ) {
                        add(extractor.sampleTime)
                    }
                    if (!extractor.advance()) break
                }
            }
        }
    }

    override suspend fun remux(uri: Uri, startUs: Long, endUs: Long): AnalysisMediaArtifact {
        require(startUs >= 0L && endUs > startUs)
        val outputSpec = withContext(Dispatchers.IO) { remuxOutputSpec(uri) }
        val output = createAnalysisFile("remux", outputSpec.extension)
        return try {
            withContext(Dispatchers.IO) {
                remuxToFile(uri, startUs, endUs, output, outputSpec.outputFormat)
            }
            FileAnalysisMediaArtifact(output, outputSpec.mimeType)
        } catch (failure: Exception) {
            cleanupAfterFailure(output, failure)
            throw failure
        }
    }

    override suspend fun proxy(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        hasAudio: Boolean,
    ): AnalysisMediaArtifact {
        require(startUs >= 0L && endUs > startUs)
        val output = createAnalysisFile("proxy")
        return try {
            val hdrMode = withContext(Dispatchers.IO) { analysisProxyHdrMode(uri) }
            withContext(Dispatchers.Main.immediate) {
                exportProxy(uri, startUs, endUs, hasAudio, hdrMode, output)
            }
            FileAnalysisMediaArtifact(output, "video/mp4")
        } catch (failure: Exception) {
            cleanupAfterFailure(output, failure)
            throw failure
        }
    }

    private suspend fun remuxOutputSpec(uri: Uri): RemuxOutputSpec = withExtractor(uri) { extractor ->
        val trackSelection = extractor.mediaTrackSelection()
        val videoMimeType = trackSelection.singleVideoTrack?.mimeType
            ?: throw IllegalArgumentException("single video track required")
        val audioMimeTypes = trackSelection.audioTracks.map { track -> track.mimeType }
        if (
            videoMimeType in WEBM_VIDEO_MIME_TYPES &&
            audioMimeTypes.all(WEBM_AUDIO_MIME_TYPES::contains)
        ) {
            RemuxOutputSpec(
                outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                mimeType = "video/webm",
                extension = "webm",
            )
        } else {
            RemuxOutputSpec(
                outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                mimeType = "video/mp4",
                extension = "mp4",
            )
        }
    }

    private suspend fun remuxToFile(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        output: File,
        outputFormat: Int,
    ) {
        withExtractor(uri) { extractor ->
            val trackSelection = extractor.mediaTrackSelection()
            val videoTrack = trackSelection.singleVideoTrack?.index
                ?: throw IllegalArgumentException("single video track required")
            val selectedTracks = trackSelection.selectedTracks.map { track -> track.index }
            val muxer = MediaMuxer(output.absolutePath, outputFormat)
            var started = false
            var primaryFailure: Throwable? = null
            try {
                val outputTracks = selectedTracks.associateWith { index ->
                    val format = extractor.getTrackFormat(index)
                    if (index == videoTrack && format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                    extractor.selectTrack(index)
                    muxer.addTrack(format)
                }
                muxer.start()
                started = true
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val bufferInfo = MediaCodec.BufferInfo()
                var videoSamples = 0
                var firstVideoSampleUs: Long? = null
                var firstVideoFlags = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val trackIndex = extractor.sampleTrackIndex
                    val sampleTimeUs = extractor.sampleTime
                    when (remuxSampleAction(trackIndex, sampleTimeUs, startUs, endUs)) {
                        RemuxSampleAction.STOP -> break
                        RemuxSampleAction.ADVANCE -> Unit
                        RemuxSampleAction.WRITE -> {
                            val sampleSize = extractor.sampleSize
                            require(sampleSize in 0..Int.MAX_VALUE.toLong())
                            val buffer = ByteBuffer.allocateDirect(sampleSize.toInt().coerceAtLeast(1))
                            val bytesRead = extractor.readSampleData(buffer, 0)
                            require(bytesRead >= 0)
                            bufferInfo.set(
                                0,
                                bytesRead,
                                sampleTimeUs - startUs,
                                muxerSampleFlags(extractor.sampleFlags),
                            )
                            muxer.writeSampleData(
                                requireNotNull(outputTracks[trackIndex]),
                                buffer,
                                bufferInfo,
                            )
                            if (trackIndex == videoTrack) {
                                if (firstVideoSampleUs == null) {
                                    firstVideoSampleUs = sampleTimeUs
                                    firstVideoFlags = extractor.sampleFlags
                                }
                                videoSamples += 1
                            }
                        }
                    }
                    if (!extractor.advance()) break
                }
                require(videoSamples > 0)
                require(firstVideoSampleUs == startUs)
                require(firstVideoFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                closeRemuxer(
                    primaryFailure = primaryFailure,
                    started = started,
                    stop = muxer::stop,
                    release = muxer::release,
                )
            }
        }
    }

    private suspend fun exportProxy(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        hasAudio: Boolean,
        hdrMode: Int,
        output: File,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val composition = buildAnalysisProxyComposition(uri, startUs, endUs, hasAudio, hdrMode)
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: Transformer.Listener
            lateinit var transformer: Transformer
            val released = AtomicBoolean(false)
            fun releaseOnMain(cancel: Boolean) {
                check(Looper.myLooper() == Looper.getMainLooper())
                if (!released.compareAndSet(false, true)) return
                try {
                    if (cancel) transformer.cancel()
                } finally {
                    transformer.removeListener(listener)
                }
            }
            transformer = Transformer.Builder(applicationContext)
                .setLooper(Looper.getMainLooper())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .apply { if (hasAudio) setAudioMimeType(MimeTypes.AUDIO_AAC) }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        releaseOnMain(cancel = false)
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        releaseOnMain(cancel = false)
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }.also { listener = it })
                .build()
            continuation.invokeOnCancellation {
                val cancel = { releaseOnMain(cancel = true) }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    cancel()
                } else {
                    Handler(Looper.getMainLooper()).post(cancel)
                }
            }
            try {
                transformer.start(composition, output.absolutePath)
            } catch (failure: Exception) {
                releaseOnMain(cancel = false)
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private suspend fun <T> withExtractor(uri: Uri, block: suspend (MediaExtractor) -> T): T {
        val descriptor = contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("SOURCE_READ_FAILED")
        return descriptor.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
                block(extractor)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                extractor.release()
            }
        }
    }

    private fun createAnalysisFile(kind: String, extension: String = "mp4"): File =
        File(applicationContext.cacheDir, "analysis-$kind-${UUID.randomUUID()}.$extension")

    private suspend fun analysisProxyHdrMode(uri: Uri): Int = withExtractor(uri) { extractor ->
        selectAnalysisProxyHdrMode((0 until extractor.trackCount).map(extractor::getTrackFormat))
    }

    private fun MediaExtractor.mediaTrackSelection(): LogicalMediaTrackSelection =
        selectAndroidMediaTracks((0 until trackCount).map(::getTrackFormat))

    private fun muxerSampleFlags(extractorFlags: Int): Int {
        require(extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
        var codecFlags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private data class RemuxOutputSpec(
        val outputFormat: Int,
        val mimeType: String,
        val extension: String,
    )

    private companion object {
        val WEBM_VIDEO_MIME_TYPES = setOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
        val WEBM_AUDIO_MIME_TYPES = setOf(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
    }
}

@UnstableApi
internal fun buildAnalysisProxyComposition(
    uri: Uri,
    startUs: Long,
    endUs: Long,
    hasAudio: Boolean,
    hdrMode: Int,
    videoEffect: Effect = ScaleAndRotateTransformation.Builder()
        .setScale(1f, 1f)
        .build(),
): Composition {
    val clipping = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionUs(startUs)
        .setEndPositionUs(endUs)
        .build()
    val mediaItem = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(clipping)
        .build()
    val editedItem = EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(!hasAudio)
        .setEffects(Effects(emptyList(), listOf(videoEffect)))
        .build()
    val sequence = if (hasAudio) {
        EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedItem))
    } else {
        EditedMediaItemSequence.withVideoFrom(listOf(editedItem))
    }
    return Composition.Builder(sequence)
        .setHdrMode(hdrMode)
        .build()
}

@UnstableApi
internal fun selectAnalysisProxyHdrMode(formats: List<MediaFormat>): Int {
    val trackSelection = selectAndroidMediaTracks(formats)
    val hasDolbyVisionRepresentation = trackSelection.videoTracks.any { track ->
        track.mimeType == VIDEO_DOLBY_VISION_MIME_TYPE
    }
    val selectedColorTransfer = trackSelection.singleVideoTrack?.let { track ->
        formats[track.index].integerValueOrNull(MediaFormat.KEY_COLOR_TRANSFER)
    }
    return if (
        hasDolbyVisionRepresentation ||
        selectedColorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
        selectedColorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
    ) {
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
    } else {
        Composition.HDR_MODE_KEEP_HDR
    }
}

private const val VIDEO_DOLBY_VISION_MIME_TYPE = "video/dolby-vision"

internal class FileAnalysisMediaArtifact(
    internal val file: File,
    override val mimeType: String,
) : AnalysisMediaArtifact {
    override val sizeBytes: Long
        get() = file.length()

    override suspend fun read(maxBytes: Int): SourceBytesReadResult = withContext(Dispatchers.IO) {
        file.inputStream().use { readBoundedSource(it, maxBytes) }
    }

    override fun close() {
        deleteOrThrow(file)
    }
}

private fun cleanupAfterFailure(file: File, primaryFailure: Exception) {
    try {
        deleteOrThrow(file)
    } catch (cleanupFailure: Exception) {
        primaryFailure.addSuppressed(cleanupFailure)
    }
}

internal enum class RemuxSampleAction { STOP, ADVANCE, WRITE }

internal fun remuxSampleAction(
    trackIndex: Int,
    sampleTimeUs: Long,
    startUs: Long,
    endUs: Long,
): RemuxSampleAction = when {
    trackIndex < 0 -> RemuxSampleAction.STOP
    sampleTimeUs < startUs -> RemuxSampleAction.ADVANCE
    sampleTimeUs >= endUs -> RemuxSampleAction.STOP
    else -> RemuxSampleAction.WRITE
}

internal fun closeRemuxer(
    primaryFailure: Throwable?,
    started: Boolean,
    stop: () -> Unit,
    release: () -> Unit,
) {
    val cleanupFailures = mutableListOf<Throwable>()
    if (started) {
        try {
            stop()
        } catch (failure: Throwable) {
            cleanupFailures += failure
        }
    }
    try {
        release()
    } catch (failure: Throwable) {
        cleanupFailures += failure
    }
    if (primaryFailure != null) {
        cleanupFailures.forEach { failure ->
            if (failure !== primaryFailure) primaryFailure.addSuppressed(failure)
        }
        return
    }
    val firstCleanupFailure = cleanupFailures.firstOrNull() ?: return
    cleanupFailures.drop(1).forEach { failure ->
        if (failure !== firstCleanupFailure) firstCleanupFailure.addSuppressed(failure)
    }
    throw firstCleanupFailure
}

private fun deleteOrThrow(file: File) {
    check(!file.exists() || file.delete()) { "ANALYSIS_TEMP_FILE_CLEANUP_FAILED" }
}
