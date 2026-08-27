package com.chill.familyvlog.media

internal data class MediaTrackDescriptor(
    val index: Int,
    val mimeType: String,
    val trackId: Int?,
)

internal data class LogicalMediaTrackSelection(
    val logicalVideoTrackCount: Int,
    val primaryVideoTrack: MediaTrackDescriptor?,
    val videoTracks: List<MediaTrackDescriptor>,
    val audioTracks: List<MediaTrackDescriptor>,
    val selectedTracks: List<MediaTrackDescriptor>,
) {
    val singleVideoTrack: MediaTrackDescriptor?
        get() = primaryVideoTrack?.takeIf { logicalVideoTrackCount == 1 }
}

internal fun selectLogicalMediaTracks(
    tracks: List<MediaTrackDescriptor>,
): LogicalMediaTrackSelection {
    val mediaTracks = tracks.filter { track -> track.isVideo() || track.isAudio() }
    val videoTracks = mediaTracks.filter(MediaTrackDescriptor::isVideo)
    val audioTracks = mediaTracks.filter(MediaTrackDescriptor::isAudio)
    val alternateBaseTrack = videoTracks.dolbyVisionBaseTrackOrNull()
    val primaryVideoTrack = alternateBaseTrack ?: videoTracks.firstOrNull()
    val logicalVideoTrackCount = if (alternateBaseTrack == null) videoTracks.size else 1
    val selectedTracks = mediaTracks.filter { track ->
        track.isAudio() || track.index == primaryVideoTrack?.index
    }
    return LogicalMediaTrackSelection(
        logicalVideoTrackCount = logicalVideoTrackCount,
        primaryVideoTrack = primaryVideoTrack,
        videoTracks = videoTracks,
        audioTracks = audioTracks,
        selectedTracks = selectedTracks,
    )
}

private fun List<MediaTrackDescriptor>.dolbyVisionBaseTrackOrNull(): MediaTrackDescriptor? {
    if (size != 2) return null
    val dolbyVisionTrack = singleOrNull { track -> track.mimeType == VIDEO_DOLBY_VISION } ?: return null
    val baseTrack = singleOrNull { track -> track.mimeType == VIDEO_HEVC || track.mimeType == VIDEO_AVC }
        ?: return null
    val dolbyVisionTrackId = dolbyVisionTrack.trackId ?: return null
    return baseTrack.takeIf { track -> track.trackId != null && track.trackId == dolbyVisionTrackId }
}

private fun MediaTrackDescriptor.isVideo(): Boolean = mimeType.startsWith("video/")

private fun MediaTrackDescriptor.isAudio(): Boolean = mimeType.startsWith("audio/")

private const val VIDEO_DOLBY_VISION = "video/dolby-vision"
private const val VIDEO_HEVC = "video/hevc"
private const val VIDEO_AVC = "video/avc"
