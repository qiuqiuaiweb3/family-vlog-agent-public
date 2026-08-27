package com.chill.familyvlog.contract

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanValidatorTest {
    @Test
    fun `restores authoritative source and raw times while computing duration from execution endpoint`() {
        val result = validatePlan(plan(selection()), understanding(frozenEnd = true))

        val valid = result as ValidationResult.Valid
        val clip = valid.value.clips.single()
        assertEquals("source-a", clip.clip.sourceId)
        assertEquals(BigDecimal("9"), clip.clip.start)
        assertEquals(BigDecimal("10.000"), clip.clip.end)
        assertEquals(BigDecimal("9.9996"), clip.executionEnd)
        assertEquals(BigDecimal("0.9996"), valid.value.totalDuration)
    }

    @Test
    fun `restores opaque event ids in model order without parsing ids or using source order`() {
        val first = ValidatedSegment(
            window(sourceOrder = 1, sourceId = "first"),
            listOf(validatedEvent("opaque-a", "1", "2")),
        )
        val second = ValidatedSegment(
            window(sourceOrder = 2, sourceId = "second"),
            listOf(validatedEvent("opaque-b", "4", "6")),
        )

        val result = validatePlan(
            plan(selection("opaque-b", "highlight"), selection("opaque-a", "ending")),
            VideoUnderstanding(listOf(first, second)),
        ) as ValidationResult.Valid

        assertEquals(listOf("opaque-b", "opaque-a"), result.value.clips.map { it.clip.eventId })
        assertEquals(listOf("second", "first"), result.value.clips.map { it.clip.sourceId })
        assertEquals(listOf(BigDecimal("4"), BigDecimal("1")), result.value.clips.map { it.clip.start })
        assertEquals(listOf(BigDecimal("6"), BigDecimal("2")), result.value.clips.map { it.clip.end })
        assertEquals(listOf("highlight", "ending"), result.value.clips.map { it.clip.storyRole })
    }

    @Test
    fun `restores events from multiple merged segments of one source`() {
        val first = ValidatedSegment(
            window(sourceId = "source-a"),
            listOf(validatedEvent("first-segment", "1", "2")),
        )
        val second = ValidatedSegment(
            window(sourceId = "source-a").copy(
                segmentId = "source-a-s02",
                segmentSourceStart = BigDecimal("5"),
            ),
            listOf(validatedEvent("second-segment", "6", "7")),
        )
        val understanding = mergeSegments(listOf(second, first))

        val result = validatePlan(
            plan(selection("second-segment"), selection("first-segment")),
            understanding,
        ) as ValidationResult.Valid

        assertEquals(listOf("second-segment", "first-segment"), result.value.clips.map { it.clip.eventId })
        assertEquals(listOf("source-a", "source-a"), result.value.clips.map { it.clip.sourceId })
    }

    @Test
    fun `accepts a plan longer than forty five seconds without dropping clips`() {
        val events = (0 until 46).map { second ->
            validatedEvent("event_${second + 1}", second.toString(), (second + 1).toString())
        }
        val raw = plan(*events.map { selection(it.event.eventId) }.toTypedArray())

        val result = validatePlan(
            raw,
            VideoUnderstanding(listOf(ValidatedSegment(window(duration = "46"), events))),
        ) as ValidationResult.Valid

        assertEquals(BigDecimal("46"), result.value.totalDuration)
        assertEquals(events.map { it.event.eventId }, result.value.clips.map { it.clip.eventId })
    }

    @Test
    fun `rejects empty unknown or globally ambiguous event ids`() {
        assertInvalid(validatePlan(EditingResponse(emptyList()), understanding()))
        assertInvalid(validatePlan(plan(selection("missing")), understanding()))

        val first = ValidatedSegment(
            window(sourceOrder = 1, sourceId = "first"),
            listOf(validatedEvent("same", "1", "2")),
        )
        val second = ValidatedSegment(
            window(sourceOrder = 2, sourceId = "second"),
            listOf(validatedEvent("same", "4", "5")),
        )
        val ambiguous = validatePlan(
            plan(selection("same")),
            VideoUnderstanding(listOf(first, second)),
        ) as ValidationResult.Invalid

        assertEquals(listOf("unresolved_event_reference"), ambiguous.errors.map { it.code })
    }

    @Test
    fun `does not add event id format local role or reason content gates`() {
        val eventId = " "
        val result = validatePlan(
            EditingResponse(listOf(EditSelection(eventId, "moment", ""))),
            VideoUnderstanding(
                listOf(
                    ValidatedSegment(
                        window(),
                        listOf(validatedEvent(eventId, "1", "2")),
                    ),
                ),
            ),
        ) as ValidationResult.Valid

        val clip = result.value.clips.single().clip
        assertEquals(eventId, clip.eventId)
        assertEquals("moment", clip.storyRole)
        assertEquals("", clip.selectionReason)
    }

    @Test
    fun `keeps input order and diagnoses duplicate reverse overlap and continuation clips`() {
        val raw = plan(
            selection("b"),
            selection("a"),
            selection("a"),
        )
        val result = validatePlan(raw, understanding(twoEvents = true, continues = true))

        val valid = result as ValidationResult.Valid
        assertEquals(listOf("b", "a", "a"), valid.value.clips.map { it.clip.eventId })
        assertTrue(valid.diagnostics.any { it.code == "duplicate_clip_reference" })
        assertTrue(valid.diagnostics.any { it.code == "source_clip_order" })
        assertTrue(valid.diagnostics.any { it.code == "source_clip_overlap" })
        assertTrue(valid.diagnostics.any { it.code == "continued_event" })
    }

    private fun assertInvalid(result: ValidationResult<*>) {
        assertTrue("Expected validation failure", result is ValidationResult.Invalid)
    }

    private fun plan(vararg clips: EditSelection) = EditingResponse(clips.toList())

    private fun selection(
        eventId: String = "a",
        storyRole: String = "opening",
    ) = EditSelection(eventId, storyRole, "visible")

    private fun understanding(
        frozenEnd: Boolean = false,
        twoEvents: Boolean = false,
        continues: Boolean = false,
    ): VideoUnderstanding {
        val events = mutableListOf(
            validatedEvent(
                "a",
                if (frozenEnd) "9" else "1",
                if (frozenEnd) "10.000" else if (twoEvents) "3.5" else "2",
                frozenEnd = frozenEnd,
                continues = continues,
            ),
        )
        if (twoEvents) events += validatedEvent("b", "3", "4")
        return VideoUnderstanding(listOf(ValidatedSegment(window(), events)))
    }

    private fun validatedEvent(
        id: String,
        start: String,
        end: String,
        frozenEnd: Boolean = false,
        continues: Boolean = false,
    ): ValidatedEvent {
        val event = UnderstandingEvent(id, BigDecimal(start), BigDecimal(end), continues, false, "description", null)
        return ValidatedEvent(event, BigDecimal(start), BigDecimal(end), if (frozenEnd) BigDecimal("9.9996") else BigDecimal(end))
    }

    private fun window(
        sourceOrder: Int = 1,
        sourceId: String = "source-a",
        duration: String = "10",
    ) = SourceWindow(
        sourceOrder = sourceOrder,
        sourceId = sourceId,
        sourceDuration = BigDecimal(duration),
        segmentId = "$sourceId-s01",
        segmentSourceStart = BigDecimal.ZERO,
        segmentSourceEnd = BigDecimal(duration),
        segmentDuration = BigDecimal(duration),
    )
}
