package com.chill.familyvlog.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RemuxTraversalTest {
    @Test
    fun `negative audio preroll advances before zero timestamp video is written`() {
        assertFalse(-1_333L >= 0L)
        assertEquals(
            RemuxSampleAction.ADVANCE,
            remuxSampleAction(trackIndex = 1, sampleTimeUs = -1_333L, startUs = 0L, endUs = 1_000L),
        )
        assertEquals(
            RemuxSampleAction.WRITE,
            remuxSampleAction(trackIndex = 0, sampleTimeUs = 0L, startUs = 0L, endUs = 1_000L),
        )
        assertEquals(
            RemuxSampleAction.STOP,
            remuxSampleAction(trackIndex = -1, sampleTimeUs = -1L, startUs = 0L, endUs = 1_000L),
        )
    }

    @Test
    fun `end boundary remains half open`() {
        assertEquals(
            RemuxSampleAction.WRITE,
            remuxSampleAction(trackIndex = 0, sampleTimeUs = 999L, startUs = 0L, endUs = 1_000L),
        )
        assertEquals(
            RemuxSampleAction.STOP,
            remuxSampleAction(trackIndex = 0, sampleTimeUs = 1_000L, startUs = 0L, endUs = 1_000L),
        )
    }

    @Test
    fun `cleanup failures are suppressed by the original remux failure`() {
        val primary = IllegalArgumentException("primary")
        val stopFailure = IllegalStateException("stop")
        val releaseFailure = IllegalStateException("release")

        closeRemuxer(
            primaryFailure = primary,
            started = true,
            stop = { throw stopFailure },
            release = { throw releaseFailure },
        )

        assertSame(stopFailure, primary.suppressed[0])
        assertSame(releaseFailure, primary.suppressed[1])
    }

    @Test
    fun `first cleanup failure is thrown when there is no primary failure`() {
        val stopFailure = IllegalStateException("stop")
        val releaseFailure = IllegalStateException("release")

        try {
            closeRemuxer(
                primaryFailure = null,
                started = true,
                stop = { throw stopFailure },
                release = { throw releaseFailure },
            )
            fail("Expected cleanup failure")
        } catch (actual: IllegalStateException) {
            assertSame(stopFailure, actual)
            assertSame(releaseFailure, actual.suppressed.single())
        }
    }
}
