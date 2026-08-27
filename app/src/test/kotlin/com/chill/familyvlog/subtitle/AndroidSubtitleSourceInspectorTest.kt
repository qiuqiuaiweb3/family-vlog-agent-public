package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.input.AudioTrackInfo
import com.chill.familyvlog.input.MediaProbe
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.VideoTrackInfo
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AndroidSubtitleSourceInspectorTest {
    private val uri = mock(Uri::class.java)

    @Test
    fun `rotation is applied to the complete published source dimensions`() = runTest {
        val inspector = inspector(validProbe(rotation = 90))

        val source = inspector.inspect(uri)

        assertSame(uri, source.uri)
        assertEquals(1_000_000L, source.durationUs)
        assertEquals(1_920, source.videoWidth)
        assertEquals(1_080, source.videoHeight)
        assertTrue(source.hasAudio)
    }

    @Test
    fun `source inspection preserves cancellation`() = runTest {
        val cancellation = CancellationException("cancel")
        val inspector = AndroidSubtitleSourceInspector(
            object : MediaProbe {
                override suspend fun inspect(uri: Uri): ProbeResult = throw cancellation
            },
        )

        val failure = runCatching { inspector.inspect(uri) }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `unreadable missing or multi track source fails at transcription boundary`() = runTest {
        listOf(
            validProbe().copy(readable = false),
            validProbe().copy(videoTrack = null),
            validProbe().copy(videoTrackCount = 2),
            validProbe().copy(
                audioTracks = listOf(audioTrack, audioTrack),
            ),
        ).forEach { probe ->
            val failure = runCatching {
                inspector(probe).inspect(uri)
            }.exceptionOrNull()

            assertTrue(failure is SubtitleException)
            failure as SubtitleException
            assertEquals(SubtitlePhase.TRANSCRIBING, failure.phase)
            assertEquals(SubtitleFailureCode.TRANSCRIPTION_FAILED, failure.code)
        }
    }

    private fun validProbe(rotation: Int = 0) = ProbeResult(
        readable = true,
        durationUs = 1_000_000L,
        resolverMimeType = "video/mp4",
        containerMimeType = "video/mp4",
        videoTrack = VideoTrackInfo(
            mimeType = "video/avc",
            width = 1_080,
            height = 1_920,
            rotationDegrees = rotation,
            frameRate = 30f,
            colorStandard = null,
            colorTransfer = null,
            colorRange = null,
        ),
        audioTracks = listOf(audioTrack),
        firstSyncFrameDecoded = true,
    )

    private fun inspector(probe: ProbeResult) = AndroidSubtitleSourceInspector(
        object : MediaProbe {
            override suspend fun inspect(uri: Uri): ProbeResult = probe
        },
    )

    private companion object {
        val audioTrack = AudioTrackInfo("audio/mp4a-latm", 2, 48_000)
    }
}
