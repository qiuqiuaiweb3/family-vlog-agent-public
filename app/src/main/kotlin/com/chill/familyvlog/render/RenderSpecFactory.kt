package com.chill.familyvlog.render

import android.media.MediaFormat
import com.chill.familyvlog.contract.ValidatedEditPlan
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.VideoTrackInfo
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import java.math.BigDecimal

fun buildRenderSpec(
    plan: ValidatedEditPlan,
    sources: Map<String, SelectedSource>,
    probes: Map<String, ProbeResult>,
): RenderSpec {
    if (plan.clips.isEmpty()) throw renderFailure()
    if (
        sources.any { (sourceId, source) ->
            source.sourceId != sourceId || probes[sourceId]?.hasSupportedTrackCount() != true
        }
    ) {
        throw renderFailure()
    }

    val prepared = plan.clips.map { validatedClip ->
        val clip = validatedClip.clip
        val source = sources[clip.sourceId]
        val probe = probes[clip.sourceId]
        val videoTrack = probe?.videoTrack
        if (
            source == null ||
            source.sourceId != clip.sourceId ||
            probe == null ||
            videoTrack == null ||
            !probe.hasSupportedTrackCount() ||
            !probe.readable ||
            probe.durationUs == null ||
            probe.durationUs <= 0 ||
            !probe.firstSyncFrameDecoded ||
            clip.start.signum() < 0 ||
            clip.end <= clip.start ||
            validatedClip.executionEnd <= clip.start ||
            validatedClip.executionEnd > clip.end
        ) {
            throw renderFailure()
        }

        val startUs = clip.start.toExactMicroseconds()
        val endUs = validatedClip.executionEnd.toExactMicroseconds()
        if (endUs <= startUs || endUs > probe.durationUs) throw renderFailure()

        PreparedClip(
            clip = RenderClip(
                uri = source.uri,
                startUs = startUs,
                endUs = endUs,
                hasAudio = probe.audioTracks.isNotEmpty(),
            ),
            videoTrack = videoTrack,
        )
    }

    if (
        prepared.first().videoTrack.colorTransfer.isKnownHdr() &&
        prepared.drop(1).any { it.videoTrack.colorTransfer.isKnownSdr() }
    ) {
        throw renderFailure()
    }

    val clips = prepared.map(PreparedClip::clip)
    return RenderSpec(
        clips = clips,
        expectsAudio = clips.any(RenderClip::hasAudio),
        canvasAspectRatio = prepared.first().videoTrack.displayAspectRatio(),
    )
}

private data class PreparedClip(
    val clip: RenderClip,
    val videoTrack: VideoTrackInfo,
)

private fun ProbeResult.hasSupportedTrackCount(): Boolean =
    videoTrackCount == 1 && audioTracks.size <= 1

private fun VideoTrackInfo.displayAspectRatio(): Float {
    val encodedWidth = width?.takeIf { it > 0 } ?: throw renderFailure()
    val encodedHeight = height?.takeIf { it > 0 } ?: throw renderFailure()
    val normalizedRotation = Math.floorMod(rotationDegrees ?: 0, 360)
    val (displayWidth, displayHeight) = when (normalizedRotation) {
        0, 180 -> encodedWidth to encodedHeight
        90, 270 -> encodedHeight to encodedWidth
        else -> throw renderFailure()
    }
    return (displayWidth.toFloat() / displayHeight.toFloat())
        .takeIf { it.isFinite() && it > 0f }
        ?: throw renderFailure()
}

private fun Int?.isKnownHdr(): Boolean =
    this == MediaFormat.COLOR_TRANSFER_HLG || this == MediaFormat.COLOR_TRANSFER_ST2084

private fun Int?.isKnownSdr(): Boolean =
    this == MediaFormat.COLOR_TRANSFER_SDR_VIDEO || this == MediaFormat.COLOR_TRANSFER_LINEAR

private fun BigDecimal.toExactMicroseconds(): Long = try {
    movePointRight(6).longValueExact()
} catch (_: ArithmeticException) {
    throw renderFailure()
}

private fun renderFailure() = PipelineException(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED)
