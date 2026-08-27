package com.chill.familyvlog.subtitle

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PcmIntervalMathTest {
    @Test
    fun `extractor sample data is read before its timestamp and keeps negative preroll`() {
        val calls = mutableListOf<String>()
        var sampleRead = false

        val sample = readExtractorSample(
            input = ByteBuffer.allocate(16),
            readSampleData = { _, _ ->
                calls += "read"
                sampleRead = true
                7
            },
            sampleTimeUs = {
                assertTrue(sampleRead)
                calls += "time"
                -36_281L
            },
        )

        assertEquals(ExtractorSample(size = 7, presentationTimeUs = -36_281L), sample)
        assertEquals(listOf("read", "time"), calls)
    }

    @Test
    fun `extractor end of stream does not request a missing timestamp`() {
        val sample = readExtractorSample(
            input = ByteBuffer.allocate(16),
            readSampleData = { _, _ -> -1 },
            sampleTimeUs = { throw AssertionError("Timestamp must not be read after end of stream") },
        )

        assertNull(sample)
    }

    @Test
    fun `local subtitle normalization accepts only one through six channels`() {
        assertEquals(1, requireSupportedTranscriptionChannelCount(1))
        assertEquals(6, requireSupportedTranscriptionChannelCount(6))

        listOf(Int.MIN_VALUE, -1, 0, 7, Int.MAX_VALUE).forEach { channelCount ->
            try {
                requireSupportedTranscriptionChannelCount(channelCount)
                fail("Expected unsupported channel count to fail")
            } catch (_: IllegalArgumentException) {
                Unit
            }
        }
    }

    @Test
    fun `sample frame conversion uses a half open ceiling boundary`() {
        assertEquals(0L, ceilFrames(0L, 16_000))
        assertEquals(1L, ceilFrames(1L, 16_000))
        assertEquals(16_000L, ceilFrames(1_000_000L, 16_000))
        assertEquals(0L, ceilFrames(-1L, 16_000))
        assertEquals(-16_000L, ceilFrames(-1_000_000L, 16_000))
    }

    @Test
    fun `pcm window excludes samples before start and at or after end`() {
        assertEquals(
            PcmFrameWindow(firstFrame = 12_000L, endFrameExclusive = 36_000L, relativeStartFrame = 0L),
            selectPcmFrameWindow(
                bufferFrameCount = 48_000,
                presentationTimeUs = 0L,
                clipStartUs = 250_000L,
                clipEndUs = 750_000L,
                sampleRate = 48_000,
            ),
        )
        assertEquals(
            PcmFrameWindow(firstFrame = 0L, endFrameExclusive = 9_600L, relativeStartFrame = 7_200L),
            selectPcmFrameWindow(
                bufferFrameCount = 9_600,
                presentationTimeUs = 400_000L,
                clipStartUs = 250_000L,
                clipEndUs = 750_000L,
                sampleRate = 48_000,
            ),
        )
        assertEquals(
            null,
            selectPcmFrameWindow(
                bufferFrameCount = 4_800,
                presentationTimeUs = 750_000L,
                clipStartUs = 250_000L,
                clipEndUs = 750_000L,
                sampleRate = 48_000,
            ),
        )
    }

    @Test
    fun `cleanup failure prevents cancellation from becoming the terminal result`() {
        val cancellation = CancellationException("cancel")
        val cleanup = IllegalStateException("cleanup")

        assertSame(cleanup, cleanupFailureToThrow(cancellation, cleanup))
        assertSame(cancellation, cleanup.suppressed.single())

        val ordinary = IllegalArgumentException("decode")
        val secondCleanup = IllegalStateException("cleanup")
        assertNull(cleanupFailureToThrow(ordinary, secondCleanup))
        assertSame(secondCleanup, ordinary.suppressed.single())
        assertSame(cleanup, cleanupFailureToThrow(null, cleanup))
    }
}
