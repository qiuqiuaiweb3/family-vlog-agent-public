package com.chill.familyvlog.input

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.chill.familyvlog.media.selectAndroidMediaTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaProbe(context: Context) : MediaProbe {
    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun inspect(uri: Uri): ProbeResult = withContext(Dispatchers.IO) {
        val resolverMimeType = resolverMimeType(uri)
        val scan = scan(uri) ?: return@withContext unreadableResult(resolverMimeType)
        val retrieverResult = readRetriever(uri, scan.firstSyncUs)
        ProbeResult(
            readable = true,
            durationUs = scan.durationUs ?: retrieverResult.durationUs,
            resolverMimeType = resolverMimeType,
            containerMimeType = retrieverResult.containerMimeType,
            videoTrack = scan.videoTrack,
            audioTracks = scan.audioTracks,
            firstSyncFrameDecoded = scan.videoTrack != null && retrieverResult.firstSyncFrameDecoded,
            videoTrackCount = scan.videoTrackCount,
        )
    }

    private fun scan(uri: Uri): ScanResult? = withScanDescriptor(uri) { descriptor ->
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(
                descriptor.fileDescriptor,
                descriptor.startOffset,
                descriptor.length,
            )
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val trackSelection = selectAndroidMediaTracks(formats)
            val durations = mutableListOf<Long>()
            formats.forEach { format ->
                format.durationUsOrNull()?.let(durations::add)
            }
            val videoTrackIndex = trackSelection.primaryVideoTrack?.index
            ScanResult(
                durationUs = durations.maxOrNull(),
                videoTrack = videoTrackIndex?.let { index -> formats[index].toVideoTrackInfo() },
                videoTrackCount = trackSelection.logicalVideoTrackCount,
                audioTracks = trackSelection.audioTracks.map { track ->
                    formats[track.index].toAudioTrackInfo()
                },
                firstSyncUs = videoTrackIndex?.let { findFirstSyncUs(extractor, it) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            releaseIgnoringException(extractor)
        }
    }

    private fun findFirstSyncUs(extractor: MediaExtractor, videoTrackIndex: Int): Long? = try {
        extractor.selectTrack(videoTrackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
        extractor.sampleTime.takeIf { timeUs ->
            timeUs >= 0 &&
                extractor.sampleTrackIndex == videoTrackIndex &&
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun readRetriever(uri: Uri, firstSyncUs: Long?): RetrieverResult =
        withRetrieverDescriptor(uri) { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
                val durationUs = retriever.metadataOrNull(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.times(1_000)
                    ?.takeIf { it > 0 }
                val containerMimeType = retriever.metadataOrNull(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                RetrieverResult(
                    durationUs = durationUs,
                    containerMimeType = containerMimeType,
                    firstSyncFrameDecoded = firstSyncUs?.let { retriever.decodeFrameAt(it) } == true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RetrieverResult(null, null, false)
            } finally {
                closeIgnoringException(retriever)
            }
        } ?: RetrieverResult(null, null, false)

    private fun MediaMetadataRetriever.decodeFrameAt(timeUs: Long): Boolean {
        try {
            val frame = getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                FRAME_WIDTH,
                FRAME_HEIGHT,
            ) ?: return false
            return try {
                frame.width > 0 && frame.height > 0
            } finally {
                recycleIgnoringException(frame)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
    }

    private fun MediaMetadataRetriever.metadataOrNull(key: Int): String? = try {
        extractMetadata(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun withScanDescriptor(uri: Uri, block: (AssetFileDescriptor) -> ScanResult?): ScanResult? {
        val descriptor = openDescriptor(uri) ?: return null
        return try {
            block(descriptor)
        } finally {
            closeIgnoringException(descriptor)
        }
    }

    private fun withRetrieverDescriptor(uri: Uri, block: (AssetFileDescriptor) -> RetrieverResult): RetrieverResult? {
        val descriptor = openDescriptor(uri) ?: return null
        return try {
            block(descriptor)
        } finally {
            closeIgnoringException(descriptor)
        }
    }

    private fun openDescriptor(uri: Uri): AssetFileDescriptor? = try {
        contentResolver.openAssetFileDescriptor(uri, "r")
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun resolverMimeType(uri: Uri): String? = try {
        contentResolver.getType(uri)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun MediaFormat.mimeTypeOrNull(): String? = optionalValue { getString(MediaFormat.KEY_MIME) }

    private fun MediaFormat.durationUsOrNull(): Long? = optionalValue { getLong(MediaFormat.KEY_DURATION) }
        ?.takeIf { it > 0 }

    private fun MediaFormat.toVideoTrackInfo(): VideoTrackInfo = VideoTrackInfo(
        mimeType = mimeTypeOrNull(),
        width = optionalValue { getInteger(MediaFormat.KEY_WIDTH) },
        height = optionalValue { getInteger(MediaFormat.KEY_HEIGHT) },
        rotationDegrees = optionalValue { getInteger(MediaFormat.KEY_ROTATION) },
        frameRate = optionalFloat(MediaFormat.KEY_FRAME_RATE),
        colorStandard = optionalValue { getInteger(MediaFormat.KEY_COLOR_STANDARD) },
        colorTransfer = optionalValue { getInteger(MediaFormat.KEY_COLOR_TRANSFER) },
        colorRange = optionalValue { getInteger(MediaFormat.KEY_COLOR_RANGE) },
    )

    private fun MediaFormat.toAudioTrackInfo(): AudioTrackInfo = AudioTrackInfo(
        mimeType = mimeTypeOrNull(),
        channelCount = optionalValue { getInteger(MediaFormat.KEY_CHANNEL_COUNT) },
        sampleRate = optionalValue { getInteger(MediaFormat.KEY_SAMPLE_RATE) },
    )

    private fun MediaFormat.optionalFloat(key: String): Float? = optionalValue { getFloat(key) }
        ?: optionalValue { getInteger(key).toFloat() }

    private fun <T> MediaFormat.optionalValue(read: MediaFormat.() -> T): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun unreadableResult(resolverMimeType: String?) = ProbeResult(
        readable = false,
        durationUs = null,
        resolverMimeType = resolverMimeType,
        containerMimeType = null,
        videoTrack = null,
        audioTracks = emptyList(),
        firstSyncFrameDecoded = false,
    )

    private fun releaseIgnoringException(extractor: MediaExtractor) {
        try {
            extractor.release()
        } catch (_: Exception) {
        }
    }

    private fun closeIgnoringException(descriptor: AssetFileDescriptor) {
        try {
            descriptor.close()
        } catch (_: Exception) {
        }
    }

    private fun closeIgnoringException(retriever: MediaMetadataRetriever) {
        try {
            retriever.close()
        } catch (_: Exception) {
        }
    }

    private fun recycleIgnoringException(bitmap: Bitmap) {
        try {
            bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    private data class ScanResult(
        val durationUs: Long?,
        val videoTrack: VideoTrackInfo?,
        val videoTrackCount: Int,
        val audioTracks: List<AudioTrackInfo>,
        val firstSyncUs: Long?,
    )

    private data class RetrieverResult(
        val durationUs: Long?,
        val containerMimeType: String?,
        val firstSyncFrameDecoded: Boolean,
    )

    private companion object {
        const val FRAME_WIDTH = 64
        const val FRAME_HEIGHT = 64
    }
}
