package com.chill.familyvlog.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingRequestFactoryTest {
    @Test
    fun `creates one exact text part without video or first-call state`() {
        val prompt = "editor system prompt"
        val repository = PromptRepository(object : PromptSource {
            override fun read(name: String): String {
                check(name == "vlog-editor-system.md")
                return prompt
            }
        })
        val understanding = """{"order_basis":"input_order","videos":[]}"""

        val request = EditingRequestFactory(repository).create(understanding)

        assertEquals(prompt, request.systemPrompt)
        assertEquals(setOf("clips"), request.schema.properties!!.keys)
        assertEquals(1, request.parts.size)
        val part = request.parts.single()
        assertTrue(part is ModelPart.Text)
        part as ModelPart.Text
        assertEquals(buildEditingTask(understanding), part.value)
        listOf("content://", "file://", "聊天历史", "人工答案").forEach { forbidden ->
            assertFalse(part.value.contains(forbidden))
        }
        assertFalse(request.parts.any { it is ModelPart.InlineVideo })
    }
}
