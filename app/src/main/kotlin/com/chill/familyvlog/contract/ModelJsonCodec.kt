package com.chill.familyvlog.contract

import java.math.BigDecimal
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ModelJsonCodec {
    fun decodeSegmentUnderstanding(json: String): SegmentUnderstanding = parse(json) { root ->
        root.requireExactKeys("understanding", UNDERSTANDING_KEYS)
        SegmentUnderstanding(
            sourceId = root.string("source_id"),
            segmentId = root.string("segment_id"),
            segmentDuration = root.decimal("segment_duration_s"),
            events = root.array("events").mapIndexed { index, element ->
                val event = element.objectValue("events[$index]")
                event.requireExactKeys("events[$index]", EVENT_KEYS)
                UnderstandingEvent(
                    eventId = event.string("event_id"),
                    startInSegment = event.decimal("start_in_segment_s"),
                    endInSegment = event.decimal("end_in_segment_s"),
                    continuesBefore = event.boolean("continues_before"),
                    continuesAfter = event.boolean("continues_after"),
                    description = event.string("description"),
                    audioDescription = event.nullableString("audio_description"),
                )
            },
        )
    }

    fun decodeEditingResponse(json: String): EditingResponse = parse(json) { root ->
        root.requireExactKeys("edit plan", EDIT_PLAN_KEYS)
        EditingResponse(
            clips = root.array("clips").mapIndexed { index, element ->
                val clip = element.objectValue("clips[$index]")
                clip.requireExactKeys("clips[$index]", CLIP_KEYS)
                EditSelection(
                    eventId = clip.string("event_id"),
                    storyRole = clip.string("story_role"),
                    selectionReason = clip.string("selection_reason"),
                )
            },
        )
    }

    fun encodeSegmentUnderstanding(value: SegmentUnderstanding): String = buildString {
        append('{')
        appendStringField("source_id", value.sourceId)
        append(',')
        appendStringField("segment_id", value.segmentId)
        append(',')
        appendNumberField("segment_duration_s", value.segmentDuration)
        append(",\"events\":[")
        value.events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            appendUnderstandingEvent(event, event.startInSegment, event.endInSegment, "start_in_segment_s", "end_in_segment_s")
        }
        append("]}")
    }

    fun encodeVideoUnderstanding(value: VideoUnderstanding): String = buildString {
        append("{\"order_basis\":\"input_order\",\"videos\":[")
        value.sources.forEachIndexed { sourceIndex, segment ->
            if (sourceIndex > 0) append(',')
            append('{')
            appendNumberField("source_order", BigDecimal.valueOf(segment.window.sourceOrder.toLong()))
            append(',')
            appendStringField("source_id", segment.window.sourceId)
            append(',')
            appendNumberField("duration_s", segment.window.sourceDuration)
            append(",\"events\":[")
            segment.events.forEachIndexed { eventIndex, event ->
                if (eventIndex > 0) append(',')
                appendUnderstandingEvent(event.event, event.start, event.end, "start_s", "end_s")
            }
            append("]}")
        }
        append("]}")
    }

    fun encodeEditPlan(value: ValidatedEditPlan): String = buildString {
        append("{\"clips\":[")
        value.clips.forEachIndexed { index, validated ->
            val clip = validated.clip
            if (index > 0) append(',')
            append('{')
            appendStringField("source_id", clip.sourceId)
            append(',')
            appendStringField("event_id", clip.eventId)
            append(',')
            appendNumberField("start_s", clip.start)
            append(',')
            appendNumberField("end_s", clip.end)
            append(',')
            appendStringField("story_role", clip.storyRole)
            append(',')
            appendStringField("selection_reason", clip.selectionReason)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendUnderstandingEvent(
        event: UnderstandingEvent,
        start: BigDecimal,
        end: BigDecimal,
        startName: String,
        endName: String,
    ) {
        append('{')
        appendStringField("event_id", event.eventId)
        append(',')
        appendNumberField(startName, start)
        append(',')
        appendNumberField(endName, end)
        append(',')
        append("\"continues_before\":${event.continuesBefore}")
        append(',')
        append("\"continues_after\":${event.continuesAfter}")
        append(',')
        appendStringField("description", event.description)
        append(",\"audio_description\":")
        if (event.audioDescription == null) append("null") else appendJsonString(event.audioDescription)
        append('}')
    }

    private fun StringBuilder.appendStringField(name: String, value: String) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendNumberField(name: String, value: BigDecimal) {
        appendJsonString(name)
        append(':')
        append(value.toPlainString())
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append(Json.encodeToString(String.serializer(), value))
    }

    private fun <T> parse(json: String, decode: (JsonObject) -> T): T = try {
        decode(Json.parseToJsonElement(json).jsonObject)
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid JSON model", error)
    }

    private fun JsonElement.objectValue(name: String): JsonObject = try {
        jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("$name must be an object", error)
    }

    private fun JsonObject.requireExactKeys(name: String, expected: Set<String>) {
        val missing = expected - keys
        val unknown = keys - expected
        require(missing.isEmpty()) { "$name is missing required fields: $missing" }
        require(unknown.isEmpty()) { "$name has unknown fields: $unknown" }
    }

    private fun JsonObject.element(name: String): JsonElement =
        get(name) ?: throw IllegalArgumentException("Missing required field $name")

    private fun JsonObject.string(name: String): String {
        val primitive = element(name).primitive(name)
        require(primitive.isString) { "$name must be a string" }
        return primitive.content
    }

    private fun JsonObject.nullableString(name: String): String? {
        val element = element(name)
        if (element is kotlinx.serialization.json.JsonNull) return null
        return element.primitive(name).let { primitive ->
            require(primitive.isString) { "$name must be a string or null" }
            primitive.content
        }
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val primitive = element(name).primitive(name)
        require(!primitive.isString) { "$name must be a boolean" }
        return primitive.booleanOrNull ?: throw IllegalArgumentException("$name must be a boolean")
    }

    private fun JsonObject.decimal(name: String): BigDecimal {
        val primitive = element(name).primitive(name)
        require(!primitive.isString && primitive.booleanOrNull == null) { "$name must be a JSON number" }
        return try {
            BigDecimal(primitive.content)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("$name must be a finite JSON number", error)
        }
    }

    private fun JsonObject.array(name: String): JsonArray = try {
        element(name).jsonArray
    } catch (error: Exception) {
        throw IllegalArgumentException("$name must be an array", error)
    }

    private fun JsonElement.primitive(name: String): JsonPrimitive =
        this as? JsonPrimitive ?: throw IllegalArgumentException("$name must be a primitive")

    private val UNDERSTANDING_KEYS = setOf("source_id", "segment_id", "segment_duration_s", "events")
    private val EVENT_KEYS = setOf(
        "event_id",
        "start_in_segment_s",
        "end_in_segment_s",
        "continues_before",
        "continues_after",
        "description",
        "audio_description",
    )
    private val EDIT_PLAN_KEYS = setOf("clips")
    private val CLIP_KEYS = setOf("event_id", "story_role", "selection_reason")
}
