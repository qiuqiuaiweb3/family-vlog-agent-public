package com.chill.familyvlog.ai

import com.google.firebase.ai.type.Schema

fun understandingResponseSchema(): Schema {
    val event = Schema.Companion.obj(
        mapOf(
            "event_id" to Schema.Companion.string(),
            "start_in_segment_s" to Schema.Companion.double(),
            "end_in_segment_s" to Schema.Companion.double(),
            "continues_before" to Schema.Companion.boolean(),
            "continues_after" to Schema.Companion.boolean(),
            "description" to Schema.Companion.string(),
            "audio_description" to Schema.Companion.string(nullable = true),
        ),
    )
    return Schema.Companion.obj(
        mapOf(
            "source_id" to Schema.Companion.string(),
            "segment_id" to Schema.Companion.string(),
            "segment_duration_s" to Schema.Companion.double(),
            "events" to Schema.Companion.array(event),
        ),
    )
}

fun editPlanResponseSchema(): Schema {
    val clip = Schema.Companion.obj(
        mapOf(
            "event_id" to Schema.Companion.string(),
            "story_role" to Schema.Companion.enumeration(listOf("opening", "development", "highlight", "ending")),
            "selection_reason" to Schema.Companion.string(),
        ),
    )
    return Schema.Companion.obj(
        mapOf("clips" to Schema.Companion.array(clip, minItems = 1)),
    )
}
