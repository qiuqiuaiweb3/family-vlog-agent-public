package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.input.MediaProbe
import java.util.concurrent.CancellationException

class AndroidSubtitleSourceInspector(
    private val mediaProbe: MediaProbe,
) : SubtitleSourceInspector {
    override suspend fun inspect(uri: Uri): SubtitleSource = try {
        val probe = mediaProbe.inspect(uri)
        val durationUs = requireNotNull(probe.durationUs).also { require(it > 0) }
        require(probe.readable && probe.videoTrackCount == 1 && probe.audioTracks.size <= 1)
        val video = requireNotNull(probe.videoTrack)
        val encodedWidth = requireNotNull(video.width).also { require(it > 0) }
        val encodedHeight = requireNotNull(video.height).also { require(it > 0) }
        val rotated = Math.floorMod(video.rotationDegrees ?: 0, 180) == 90
        SubtitleSource(
            uri = uri,
            durationUs = durationUs,
            videoWidth = if (rotated) encodedHeight else encodedWidth,
            videoHeight = if (rotated) encodedWidth else encodedHeight,
            hasAudio = probe.audioTracks.isNotEmpty(),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: SubtitleException) {
        throw failure
    } catch (failure: Exception) {
        throw transcriptionFailure(failure)
    }
}
