package com.chill.familyvlog.ai

import com.chill.familyvlog.contract.SourceWindow
import java.math.BigDecimal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstandingRequestFactoryTest {
    @Test
    fun `creates one unchanged inline video before the exact understanding task`() {
        val prompt = "understanding system prompt"
        val repository = PromptRepository(object : PromptSource {
            override fun read(name: String): String {
                check(name == "video-understanding-system.md")
                return prompt
            }
        })
        val window = SourceWindow(
            sourceOrder = 1,
            sourceId = "video_01",
            sourceDuration = BigDecimal("3.25"),
            segmentId = "video_01_s01",
            segmentSourceStart = BigDecimal.ZERO,
            segmentSourceEnd = BigDecimal("3.25"),
            segmentDuration = BigDecimal("3.25"),
        )
        val metadata = buildJsonObject { put("captured_at", "2026-08-13T10:00:00+09:00") }
        val bytes = byteArrayOf(0, 1, 2, 0x7f)

        val request = UnderstandingRequestFactory(repository).create(
            window = window,
            sourceMetadata = metadata,
            bytes = bytes,
            inlineVideoMimeType = "video/mp4",
        )

        assertEquals(prompt, request.systemPrompt)
        assertEquals(setOf("source_id", "segment_id", "segment_duration_s", "events"), request.schema.properties!!.keys)
        assertEquals(2, request.parts.size)
        val video = request.parts[0]
        assertTrue(video is ModelPart.InlineVideo)
        video as ModelPart.InlineVideo
        assertArrayEquals(bytes, video.bytes)
        assertEquals("video/mp4", video.mimeType)
        val task = request.parts[1]
        assertTrue(task is ModelPart.Text)
        task as ModelPart.Text
        assertEquals(buildUnderstandingTask(window, metadata), task.value)
    }

    @Test
    fun `request budget wrappers use the exact prompt task and MIME`() {
        val prompt = "system\n\"prompt"
        val repository = PromptRepository(object : PromptSource {
            override fun read(name: String): String = prompt
        })
        val window = SourceWindow(
            sourceOrder = 1,
            sourceId = "video_01",
            sourceDuration = BigDecimal("3.25"),
            segmentId = "video_01_s01",
            segmentSourceStart = BigDecimal.ZERO,
            segmentSourceEnd = BigDecimal("3.25"),
            segmentDuration = BigDecimal("3.25"),
        )
        val metadata = buildJsonObject { put("captured_at", "2026-08-19T10:00:00+09:00") }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val mimeType = "video/mp4"
        val factory = UnderstandingRequestFactory(repository)
        val task = buildUnderstandingTask(window, metadata)

        assertEquals(
            InlineRequestBudget.maxInlineVideoBytes(prompt, task, mimeType),
            factory.maxInlineVideoBytes(window, metadata, mimeType),
        )
        assertEquals(
            InlineRequestBudget.estimateTotalBytes(bytes, prompt, task, mimeType),
            factory.estimateTotalBytes(window, metadata, bytes, mimeType),
        )
    }
}
