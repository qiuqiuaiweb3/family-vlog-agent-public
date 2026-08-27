package com.chill.familyvlog.input

import android.net.Uri

data class ProbeResult(
    val readable: Boolean,
    val durationUs: Long?,
    val resolverMimeType: String?,
    val containerMimeType: String?,
    val videoTrack: VideoTrackInfo?,
    val audioTracks: List<AudioTrackInfo>,
    val firstSyncFrameDecoded: Boolean,
    val videoTrackCount: Int = if (videoTrack == null) 0 else 1,
)

data class VideoTrackInfo(
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int?,
    val frameRate: Float?,
    val colorStandard: Int?,
    val colorTransfer: Int?,
    val colorRange: Int?,
)

data class AudioTrackInfo(
    val mimeType: String?,
    val channelCount: Int?,
    val sampleRate: Int?,
)

interface MediaProbe {
    suspend fun inspect(uri: Uri): ProbeResult
}
