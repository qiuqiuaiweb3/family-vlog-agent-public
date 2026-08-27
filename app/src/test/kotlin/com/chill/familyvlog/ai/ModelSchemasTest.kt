package com.chill.familyvlog.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSchemasTest {
    @Test
    fun `understanding schema has only current required fields including nullable audio description`() {
        val root = understandingResponseSchema()

        val rootProperties = root.properties!!
        assertEquals(setOf("source_id", "segment_id", "segment_duration_s", "events"), rootProperties.keys)
        assertEquals(rootProperties.keys, root.required!!.toSet())
        val event = rootProperties.getValue("events").items!!
        val eventProperties = event.properties!!
        assertEquals(
            setOf(
                "event_id", "start_in_segment_s", "end_in_segment_s", "continues_before",
                "continues_after", "description", "audio_description",
            ),
            eventProperties.keys,
        )
        assertEquals(eventProperties.keys, event.required!!.toSet())
        assertEquals(true, eventProperties.getValue("audio_description").nullable)
    }

    @Test
    fun `edit schema requires one clip with only current fields and supported story roles`() {
        val root = editPlanResponseSchema()

        val rootProperties = root.properties!!
        assertEquals(setOf("clips"), rootProperties.keys)
        assertEquals(rootProperties.keys, root.required!!.toSet())
        val clips = rootProperties.getValue("clips")
        assertEquals(1, clips.minItems)
        val clip = clips.items!!
        val clipProperties = clip.properties!!
        assertEquals(
            setOf("event_id", "story_role", "selection_reason"),
            clipProperties.keys,
        )
        assertEquals(clipProperties.keys, clip.required!!.toSet())
        assertEquals(setOf("opening", "development", "highlight", "ending"), clipProperties.getValue("story_role").enum!!.toSet())
    }
}
