package com.chill.familyvlog.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineRequestBudgetTest {
    @Test
    fun `estimate counts canonical base64 and escaped UTF-8 JSON strings`() {
        val estimated = InlineRequestBudget.estimateTotalBytes(
            rawBytes = byteArrayOf(1, 2, 3, 4),
            systemPrompt = "a\n\"",
            userTask = "中文",
            mimeType = "video/mp4",
        )

        // 4 个原始字节编码为 8 个 Base64 字节；三个 JSON 字符串分别占 7、8、11 个 UTF-8 字节。
        assertEquals(1_000_034L, estimated)
    }

    @Test
    fun `exact request limit is rejected`() {
        val estimated = InlineRequestBudget.estimateTotalBytes(
            rawByteCount = 14_249_994,
            systemPrompt = "aa",
            userTask = "",
            mimeType = "",
        )

        assertEquals(20_000_000L, estimated)
        assertFalse(
            InlineRequestBudget.fits(
                rawByteCount = 14_249_994,
                systemPrompt = "aa",
                userTask = "",
                mimeType = "",
            ),
        )
    }

    @Test
    fun `maximum raw bytes is the last canonical Base64 group below the limit`() {
        val maximum = InlineRequestBudget.maxInlineVideoBytes(
            systemPrompt = "aa",
            userTask = "",
            mimeType = "",
        )

        assertEquals(14_249_991, maximum)
        assertTrue(
            InlineRequestBudget.fits(
                rawByteCount = maximum,
                systemPrompt = "aa",
                userTask = "",
                mimeType = "",
            ),
        )
        assertFalse(
            InlineRequestBudget.fits(
                rawByteCount = maximum + 1,
                systemPrompt = "aa",
                userTask = "",
                mimeType = "",
            ),
        )
    }

    @Test
    fun `canonical Base64 length advances only at three-byte boundaries`() {
        assertEquals(0L, InlineRequestBudget.base64EncodedLength(0))
        assertEquals(4L, InlineRequestBudget.base64EncodedLength(1))
        assertEquals(4L, InlineRequestBudget.base64EncodedLength(2))
        assertEquals(4L, InlineRequestBudget.base64EncodedLength(3))
        assertEquals(8L, InlineRequestBudget.base64EncodedLength(4))
    }
}
