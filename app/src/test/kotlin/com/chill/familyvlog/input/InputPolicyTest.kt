package com.chill.familyvlog.input

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPolicyTest {
    @Test
    fun `rejection priority follows unreadable duration video track then decoded sync frame`() {
        assertEquals(
            SourceDecision.Rejected(RejectionReason.UNREADABLE),
            evaluate(probe(readable = false, durationUs = 1, videoTrack = videoTrack(), firstSyncFrameDecoded = true)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.NON_POSITIVE_DURATION),
            evaluate(probe(durationUs = 0, videoTrack = videoTrack(), firstSyncFrameDecoded = true)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.NO_VIDEO_TRACK),
            evaluate(probe(durationUs = 1, videoTrack = null, firstSyncFrameDecoded = true)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.VIDEO_DECODE_FAILED),
            evaluate(probe(durationUs = 1, videoTrack = videoTrack(), firstSyncFrameDecoded = false)),
        )
    }

    @Test
    fun `rejection priority resolves overlapping failed gates`() {
        assertEquals(
            SourceDecision.Rejected(RejectionReason.UNREADABLE),
            evaluate(probe(readable = false, durationUs = 0, videoTrack = null, firstSyncFrameDecoded = false)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.NON_POSITIVE_DURATION),
            evaluate(probe(readable = true, durationUs = 0, videoTrack = null, firstSyncFrameDecoded = false)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.NO_VIDEO_TRACK),
            evaluate(probe(readable = true, durationUs = 1, videoTrack = null, firstSyncFrameDecoded = false)),
        )
        assertEquals(
            SourceDecision.Rejected(RejectionReason.VIDEO_DECODE_FAILED),
            evaluate(probe(readable = true, durationUs = 1, videoTrack = videoTrack(), firstSyncFrameDecoded = false)),
        )
    }

    @Test
    fun `long silent and incomplete diagnostics remain accepted when media is usable`() {
        val longSilent = probe(
            durationUs = 361_000_000,
            resolverMimeType = null,
            containerMimeType = null,
            videoTrack = videoTrack(
                width = null,
                height = null,
                rotationDegrees = null,
                frameRate = null,
                colorStandard = null,
                colorTransfer = null,
                colorRange = null,
            ),
            audioTracks = emptyList(),
            firstSyncFrameDecoded = true,
        )
        val portraitWithHdrDiagnostic = probe(
            durationUs = 1,
            videoTrack = videoTrack(width = 720, height = 1280, rotationDegrees = 90, colorTransfer = 6),
            firstSyncFrameDecoded = true,
        )

        assertEquals(SourceDecision.Accepted, evaluate(longSilent))
        assertEquals(SourceDecision.Accepted, evaluate(portraitWithHdrDiagnostic))
    }

    @Test
    fun `missing or conflicting MIME diagnostics do not reject decodable video`() {
        val missingResolverType = probe(
            resolverMimeType = null,
            containerMimeType = "video/mp4",
        )
        val conflictingTypes = probe(
            resolverMimeType = "image/jpeg",
            containerMimeType = "application/octet-stream",
        )

        assertEquals(SourceDecision.Accepted, evaluate(missingResolverType))
        assertEquals(SourceDecision.Accepted, evaluate(conflictingTypes))
    }

    private fun probe(
        readable: Boolean = true,
        durationUs: Long? = 1,
        resolverMimeType: String? = "video/mp4",
        containerMimeType: String? = "video/mp4",
        videoTrack: VideoTrackInfo? = videoTrack(),
        audioTracks: List<AudioTrackInfo> = listOf(AudioTrackInfo("audio/mp4a-latm", 2, 44_100)),
        firstSyncFrameDecoded: Boolean = true,
    ) = ProbeResult(
        readable = readable,
        durationUs = durationUs,
        resolverMimeType = resolverMimeType,
        containerMimeType = containerMimeType,
        videoTrack = videoTrack,
        audioTracks = audioTracks,
        firstSyncFrameDecoded = firstSyncFrameDecoded,
    )

    private fun videoTrack(
        mimeType: String? = "video/avc",
        width: Int? = 64,
        height: Int? = 64,
        rotationDegrees: Int? = 0,
        frameRate: Float? = 30f,
        colorStandard: Int? = 1,
        colorTransfer: Int? = 3,
        colorRange: Int? = 2,
    ) = VideoTrackInfo(
        mimeType,
        width,
        height,
        rotationDegrees,
        frameRate,
        colorStandard,
        colorTransfer,
        colorRange,
    )
}
