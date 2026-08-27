package com.chill.familyvlog.ai

import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.VideoTrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseVideoMimeTest {
    @Test
    fun `normalizes a supported container MIME without consulting other labels`() {
        val result = resolveFirebaseInlineVideoMime(
            probe(containerMimeType = "  Video/MP4 ; codecs=avc1", resolverMimeType = "application/octet-stream"),
        )

        assertEquals("video/mp4", result)
    }

    @Test
    fun `accepts WebM from the container even when provider MIME is absent`() {
        assertEquals(
            "video/webm",
            resolveFirebaseInlineVideoMime(probe(containerMimeType = "video/webm", resolverMimeType = null)),
        )
    }

    @Test
    fun `missing container fails even when provider claims MP4`() {
        assertMimeFailure(
            AiFailureCode.INLINE_VIDEO_MIME_UNKNOWN,
            probe(containerMimeType = null, resolverMimeType = "video/mp4"),
        )
    }

    @Test
    fun `blank container fails as unknown`() {
        assertMimeFailure(
            AiFailureCode.INLINE_VIDEO_MIME_UNKNOWN,
            probe(containerMimeType = " ; codecs=avc1", resolverMimeType = "video/mp4"),
        )
    }

    @Test
    fun `track codec MIME never substitutes for a missing container MIME`() {
        listOf("video/avc", "video/hevc").forEach { trackMime ->
            assertMimeFailure(
                AiFailureCode.INLINE_VIDEO_MIME_UNKNOWN,
                probe(containerMimeType = null, resolverMimeType = null, trackMimeType = trackMime),
            )
        }
    }

    @Test
    fun `unsupported nonblank container fails without rewriting it`() {
        assertMimeFailure(
            AiFailureCode.INLINE_VIDEO_MIME_UNSUPPORTED,
            probe(containerMimeType = "video/x-matroska", resolverMimeType = "video/mp4"),
        )
    }

    private fun assertMimeFailure(expected: AiFailureCode, probe: ProbeResult) {
        val failure = assertThrows(AiFailureException::class.java) {
            resolveFirebaseInlineVideoMime(probe)
        }

        assertEquals(expected, failure.code)
        assertEquals(expected.name, failure.message)
    }

    private fun probe(
        containerMimeType: String?,
        resolverMimeType: String?,
        trackMimeType: String? = "video/avc",
    ) = ProbeResult(
        readable = true,
        durationUs = 1_000_000,
        resolverMimeType = resolverMimeType,
        containerMimeType = containerMimeType,
        videoTrack = VideoTrackInfo(
            mimeType = trackMimeType,
            width = 16,
            height = 32,
            rotationDegrees = 0,
            frameRate = 30f,
            colorStandard = null,
            colorTransfer = null,
            colorRange = null,
        ),
        audioTracks = emptyList(),
        firstSyncFrameDecoded = true,
    )
}
