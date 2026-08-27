package com.chill.familyvlog.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdAllocatorTest {
    @Test
    fun `source identities are one based and preserve at least two digit identifiers`() {
        val first = sourceIdentityAt(0)
        val tenth = sourceIdentityAt(9)
        val eleventh = sourceIdentityAt(10)
        val hundredth = sourceIdentityAt(99)

        assertEquals(1, first.sourceOrder)
        assertEquals("video_01", first.sourceId)
        assertEquals(10, tenth.sourceOrder)
        assertEquals("video_10", tenth.sourceId)
        assertEquals(11, eleventh.sourceOrder)
        assertEquals("video_11", eleventh.sourceId)
        assertEquals(100, hundredth.sourceOrder)
        assertEquals("video_100", hundredth.sourceId)
    }
}
