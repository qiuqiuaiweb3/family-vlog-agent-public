package com.chill.familyvlog.input

sealed interface SourceDecision {
    data object Accepted : SourceDecision

    data class Rejected(val reason: RejectionReason) : SourceDecision
}

enum class RejectionReason {
    UNREADABLE,
    NON_POSITIVE_DURATION,
    NO_VIDEO_TRACK,
    VIDEO_DECODE_FAILED,
}

fun evaluate(probe: ProbeResult): SourceDecision = when {
    !probe.readable -> SourceDecision.Rejected(RejectionReason.UNREADABLE)
    probe.durationUs == null || probe.durationUs <= 0 -> SourceDecision.Rejected(RejectionReason.NON_POSITIVE_DURATION)
    probe.videoTrack == null -> SourceDecision.Rejected(RejectionReason.NO_VIDEO_TRACK)
    !probe.firstSyncFrameDecoded -> SourceDecision.Rejected(RejectionReason.VIDEO_DECODE_FAILED)
    else -> SourceDecision.Accepted
}
