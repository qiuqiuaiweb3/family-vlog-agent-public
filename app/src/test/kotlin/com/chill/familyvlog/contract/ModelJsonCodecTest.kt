package com.chill.familyvlog.contract

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelJsonCodecTest {
    @Test
    fun `rejects missing required event field`() {
        assertDecodeFails(validUnderstandingJson().replace("\"description\":\"laughing\",", ""))
    }

    @Test
    fun `rejects unknown fields including retired understanding fields`() {
        assertDecodeFails(validUnderstandingJson().replace("{", "{\"order\":0,"))
        assertDecodeFails(validUnderstandingJson().replace("{", "{\"unexpected\":true,"))
        assertDecodeFails(validUnderstandingJson().replace("\"events\":[{", "\"events\":[{\"unexpected\":true,"))
        assertDecodeFails(validUnderstandingJson().replace("\"source_id\":\"source-a\",", ""))
        assertEditingResponseDecodeFails(validEditingResponseJson().replace("{", "{\"unexpected\":true,"))
        assertEditingResponseDecodeFails(validEditingResponseJson().replace("\"clips\":[{", "\"clips\":[{\"unexpected\":true,"))
        assertEditingResponseDecodeFails("{}")
        assertEditingResponseDecodeFails(validEditingResponseJson().replace(",\n  \"selection_reason\":\"warm moment\"", ""))
    }

    @Test
    fun `rejects wrong field types`() {
        assertDecodeFails(validUnderstandingJson().replace("\"continues_before\":false", "\"continues_before\":\"false\""))
        assertEditingResponseDecodeFails(validEditingResponseJson().replace("\"event_id\":\"event-a\"", "\"event_id\":1"))
    }

    @Test
    fun `rejects non finite numeric tokens`() {
        assertDecodeFails(validUnderstandingJson().replace("\"segment_duration_s\":10", "\"segment_duration_s\":NaN"))
    }

    @Test
    fun `rejects retired and redundant editing response fields`() {
        assertEditingResponseDecodeFails(validEditingResponseJson().replace("{", "{\"total_duration_s\":1.25,"))
        assertEditingResponseDecodeFails(
            """{"clips":[{"source_id":"source-a","event_id":"event-a","start_s":1.25,"end_s":2.5,"story_role":"opening","selection_reason":"warm moment"}]}""",
        )
    }

    @Test
    fun `decodes only event selection fields from the model response`() {
        val response = ModelJsonCodec.decodeEditingResponse(validEditingResponseJson())

        assertEquals(EditSelection("event-a", "opening", "warm moment"), response.clips.single())
    }

    @Test
    fun `accepts required nullable audio description and preserves decimal precision`() {
        val decoded = ModelJsonCodec.decodeSegmentUnderstanding(validUnderstandingJson())

        assertEquals(BigDecimal("1.2500"), decoded.events.single().startInSegment)
        assertNull(decoded.events.single().audioDescription)
    }

    @Test
    fun `accepts exponent notation and encodes ordinary decimal JSON`() {
        val decoded = ModelJsonCodec.decodeSegmentUnderstanding(
            validUnderstandingJson().replace("\"segment_duration_s\":10", "\"segment_duration_s\":1e1"),
        )

        val encoded = ModelJsonCodec.encodeSegmentUnderstanding(decoded)

        assertEquals(0, BigDecimal("10").compareTo(decoded.segmentDuration))
        assertEquals(false, encoded.contains("1e1"))
        assertEquals(false, encoded.contains("E"))
        assertEquals(0, BigDecimal("10").compareTo(ModelJsonCodec.decodeSegmentUnderstanding(encoded).segmentDuration))
    }

    @Test
    fun `encodes final artifacts with exact nested contracts and decimal values`() {
        val event = UnderstandingEvent(
            "event-a", BigDecimal("1.2500"), BigDecimal("2.5000"), false, false, "laughing", null,
        )
        val window = SourceWindow(
            1, "source-a", BigDecimal("3.0000"), "segment-a", BigDecimal.ZERO, BigDecimal("3.0000"), BigDecimal("3.0000"),
        )
        val understanding = VideoUnderstanding(
            listOf(ValidatedSegment(window, listOf(ValidatedEvent(event, BigDecimal("1.2500"), BigDecimal("2.5000"), BigDecimal("2.5000"))))),
        )
        val editClip = EditClip("source-a", "event-a", BigDecimal("1.2500"), BigDecimal("2.5000"), "opening", "warm moment")
        val plan = ValidatedEditPlan(
            listOf(ValidatedEditClip(editClip, BigDecimal("2.5000"))),
            BigDecimal("1.2500"),
        )

        val understandingJson = ModelJsonCodec.encodeVideoUnderstanding(understanding)
        val planJson = ModelJsonCodec.encodeEditPlan(plan)

        assertVideoUnderstandingContract(Json.parseToJsonElement(understandingJson).jsonObject)
        assertEditPlanContract(Json.parseToJsonElement(planJson).jsonObject)
        assertEquals(false, understandingJson.contains("E"))
        assertEquals(false, planJson.contains("E"))
        assertEditingResponseDecodeFails(planJson)
    }

    @Test
    fun `encodes merged segments from one source as one video item`() {
        val first = ValidatedSegment(
            SourceWindow(
                1, "source-a", BigDecimal("10"), "source-a-s01",
                BigDecimal.ZERO, BigDecimal("6"), BigDecimal("6"),
            ),
            listOf(validatedEvent("early", "1", "2")),
        )
        val second = ValidatedSegment(
            SourceWindow(
                1, "source-a", BigDecimal("10"), "source-a-s02",
                BigDecimal("4"), BigDecimal("10"), BigDecimal("6"),
            ),
            listOf(validatedEvent("later", "7", "8")),
        )

        val root = Json.parseToJsonElement(
            ModelJsonCodec.encodeVideoUnderstanding(mergeSegments(listOf(second, first))),
        ).jsonObject

        val videos = root["videos"]!!.jsonArray
        assertEquals(1, videos.size)
        assertEquals(listOf("early", "later"), videos.single().jsonObject["events"]!!.jsonArray.map {
            it.jsonObject["event_id"]!!.jsonPrimitive.content
        })
    }

    private fun validatedEvent(id: String, start: String, end: String): ValidatedEvent {
        val event = UnderstandingEvent(
            id, BigDecimal(start), BigDecimal(end), false, false, "description", null,
        )
        return ValidatedEvent(event, BigDecimal(start), BigDecimal(end), BigDecimal(end))
    }

    private fun assertDecodeFails(json: String) {
        try {
            ModelJsonCodec.decodeSegmentUnderstanding(json)
            throw AssertionError("Expected strict JSON decoding to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertEditingResponseDecodeFails(json: String) {
        try {
            ModelJsonCodec.decodeEditingResponse(json)
            throw AssertionError("Expected strict JSON decoding to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertVideoUnderstandingContract(root: JsonObject) {
        assertEquals(setOf("order_basis", "videos"), root.keys)
        assertString(root["order_basis"]!!, "input_order")
        val video = root["videos"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("source_order", "source_id", "duration_s", "events"), video.keys)
        assertNumber(video["source_order"]!!, "1")
        assertString(video["source_id"]!!, "source-a")
        assertNumber(video["duration_s"]!!, "3.0000")
        val event = video["events"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("event_id", "start_s", "end_s", "continues_before", "continues_after", "description", "audio_description"),
            event.keys,
        )
        assertString(event["event_id"]!!, "event-a")
        assertNumber(event["start_s"]!!, "1.2500")
        assertNumber(event["end_s"]!!, "2.5000")
        assertBoolean(event["continues_before"]!!, false)
        assertBoolean(event["continues_after"]!!, false)
        assertString(event["description"]!!, "laughing")
        assertTrue(event["audio_description"] is JsonNull)
        assertFalse(root.containsKey("order"))
        assertFalse(root.containsKey("total_duration_s"))
        assertFalse(root.containsKey("min_clips"))
    }

    private fun assertEditPlanContract(root: JsonObject) {
        assertEquals(setOf("clips"), root.keys)
        val clip = root["clips"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("source_id", "event_id", "start_s", "end_s", "story_role", "selection_reason"), clip.keys)
        assertString(clip["source_id"]!!, "source-a")
        assertString(clip["event_id"]!!, "event-a")
        assertNumber(clip["start_s"]!!, "1.2500")
        assertNumber(clip["end_s"]!!, "2.5000")
        assertString(clip["story_role"]!!, "opening")
        assertString(clip["selection_reason"]!!, "warm moment")
        assertFalse(root.containsKey("order"))
        assertFalse(root.containsKey("total_duration_s"))
        assertFalse(root.containsKey("min_clips"))
    }

    private fun assertString(element: kotlinx.serialization.json.JsonElement, expected: String) {
        assertTrue(element.jsonPrimitive.isString)
        assertEquals(expected, element.jsonPrimitive.content)
    }

    private fun assertNumber(element: kotlinx.serialization.json.JsonElement, expected: String) {
        assertFalse(element.jsonPrimitive.isString)
        assertEquals(expected, element.jsonPrimitive.content)
    }

    private fun assertBoolean(element: kotlinx.serialization.json.JsonElement, expected: Boolean) {
        assertFalse(element.jsonPrimitive.isString)
        assertEquals(expected, element.jsonPrimitive.boolean)
    }

    private fun validUnderstandingJson() = """
        {
          "source_id":"source-a",
          "segment_id":"segment-a",
          "segment_duration_s":10,
          "events":[{
            "event_id":"event-a",
            "start_in_segment_s":1.2500,
            "end_in_segment_s":2.5,
            "continues_before":false,
            "continues_after":false,
            "description":"laughing",
            "audio_description":null
          }]
        }
    """.trimIndent()

    private fun validEditingResponseJson() = """
        {"clips":[{
          "event_id":"event-a",
          "story_role":"opening",
          "selection_reason":"warm moment"
        }]}
    """.trimIndent()
}
