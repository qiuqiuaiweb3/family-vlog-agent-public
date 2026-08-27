package com.chill.familyvlog.contract

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UnderstandingValidatorTest {
    @Test
    fun `accepts an event starting at zero`() {
        val result = validateSegment(raw(event(start = "0", end = "1")), window())

        val valid = result as ValidationResult.Valid
        assertEquals(BigDecimal.ZERO, valid.value.events.single().start)
    }

    @Test
    fun `requires one based source order`() {
        val zeroBased = validateSegment(raw(), window(sourceOrder = 0))
        val oneBased = validateSegment(raw(), window(sourceOrder = 1))

        assertTrue(zeroBased is ValidationResult.Invalid)
        val invalid = zeroBased as ValidationResult.Invalid
        assertTrue(invalid.errors.any { it.code == "invalid_source_order" })
        assertTrue(oneBased is ValidationResult.Valid)
    }

    @Test
    fun `rejects negative start and non increasing ranges`() {
        assertInvalid(validateSegment(raw(event(start = "-0.1", end = "1")), window()))
        assertInvalid(validateSegment(raw(event(start = "1", end = "1")), window()))
        assertInvalid(validateSegment(raw(event(start = "2", end = "1")), window()))
    }

    @Test
    fun `rejects ordinary end beyond model segment duration`() {
        assertInvalid(validateSegment(raw(event(start = "9", end = "10.001")), window()))
    }

    @Test
    fun `maps only a frozen terminal rounding exception to execution end`() {
        val mapped = validateSegment(raw(event(start = "9", end = "10.000")), window(sourceEnd = "9.9996"))
        val ordinary = validateSegment(raw(event(start = "8", end = "9")), window(sourceEnd = "9.9996"))

        val mappedEvent = (mapped as ValidationResult.Valid).value.events.single()
        val ordinaryEvent = (ordinary as ValidationResult.Valid).value.events.single()
        assertEquals(BigDecimal("10.000"), mappedEvent.end)
        assertEquals(BigDecimal("9.9996"), mappedEvent.executionEnd)
        assertEquals(BigDecimal("9"), ordinaryEvent.executionEnd)
    }

    @Test
    fun `maps quantized terminal end from a nonzero source offset`() {
        val result = validateSegment(
            raw(event(start = "9", end = "10.000")),
            window(sourceStart = "5", sourceEnd = "14.9996", sourceDuration = "20"),
        )

        val event = (result as ValidationResult.Valid).value.events.single()
        assertEquals(BigDecimal("15.000"), event.end)
        assertEquals(BigDecimal("14.9996"), event.executionEnd)
    }

    @Test
    fun `rejects nonquantized terminal overshoots even within tolerance`() {
        assertInvalid(validateSegment(raw(event(start = "9", end = "9.9997")), window(sourceEnd = "9.9996")))
        assertInvalid(validateSegment(raw(event(start = "9", end = "9.9998")), window(sourceEnd = "9.9996")))
    }

    @Test
    fun `accepts a quantized terminal end exactly half a millisecond above source end`() {
        val result = validateSegment(raw(event(start = "9", end = "10.000")), window(sourceEnd = "9.9995"))

        val event = (result as ValidationResult.Valid).value.events.single()
        assertEquals(BigDecimal("9.9995"), event.executionEnd)
    }

    @Test
    fun `rejects terminal overshoot greater than frozen tolerance`() {
        assertInvalid(validateSegment(raw(event(start = "9", end = "10.001")), window(sourceEnd = "9.9996")))
    }

    @Test
    fun `rejects blank descriptions`() {
        assertInvalid(validateSegment(raw(event(description = " \t")), window()))
    }

    @Test
    fun `requires a legal half open reporting core`() {
        listOf(
            window(coreStart = "-1", coreEnd = "5") to "invalid_reporting_core_start",
            window(coreStart = "5", coreEnd = "5") to "invalid_reporting_core_end",
            window(coreStart = "6", coreEnd = "5") to "invalid_reporting_core_end",
            window(coreStart = "0", coreEnd = "10.001") to "reporting_core_exceeds_segment",
        ).forEach { (invalidWindow, expectedCode) ->
            assertInvalidWithCode(validateSegment(raw(), invalidWindow), expectedCode)
        }
    }

    @Test
    fun `accepts ordinary decimal event and reporting core coordinates`() {
        val result = validateSegment(
            raw(event(start = "1.250", end = "2.750")),
            window(coreStart = "0.500", coreEnd = "5.500"),
        )

        val event = (result as ValidationResult.Valid).value.events.single()
        assertEquals(BigDecimal("1.250"), event.start)
        assertEquals(BigDecimal("2.750"), event.end)
    }

    @Test
    fun `assigns complete events by midpoint to a half open reporting core`() {
        val atLeftBoundary = validateSegment(
            raw(event(start = "1", end = "3")),
            window(coreStart = "2", coreEnd = "4"),
        )
        val atRightBoundary = validateSegment(
            raw(event(start = "3", end = "5")),
            window(coreStart = "2", coreEnd = "4"),
        )

        assertTrue(atLeftBoundary is ValidationResult.Valid)
        assertInvalid(atRightBoundary)
    }

    @Test
    fun `requires continued events to publish only their reporting core intersection`() {
        val insideCore = validateSegment(
            raw(event(start = "2", end = "3", continuesBefore = true, continuesAfter = true)),
            window(coreStart = "2", coreEnd = "4"),
        )
        val repeatsLeftContext = validateSegment(
            raw(event(start = "1", end = "3", continuesAfter = true)),
            window(coreStart = "2", coreEnd = "4"),
        )
        val repeatsRightContext = validateSegment(
            raw(event(start = "3", end = "5", continuesBefore = true)),
            window(coreStart = "2", coreEnd = "4"),
        )

        assertTrue(insideCore is ValidationResult.Valid)
        assertInvalidWithCode(repeatsLeftContext, "continued_event_outside_reporting_core")
        assertInvalidWithCode(repeatsRightContext, "continued_event_outside_reporting_core")
    }

    @Test
    fun `reports input inversion and stably sorts events by execution times`() {
        val result = validateSegment(
            raw(
                event(id = "late", start = "2", end = "3"),
                event(id = "first-same-key", start = "0", end = "1"),
                event(id = "second-same-key", start = "0", end = "1"),
            ),
            window(),
        )

        val valid = result as ValidationResult.Valid
        assertEquals(listOf("first-same-key", "second-same-key", "late"), valid.value.events.map { it.event.eventId })
        assertTrue(valid.diagnostics.any { it.code == "input_event_order" })
    }

    @Test
    fun `rejects invalid window and mismatched model identity or duration`() {
        assertInvalid(validateSegment(raw(), window(sourceEnd = "0")))
        assertInvalid(validateSegment(raw(), window(sourceDuration = "9.9")))
        assertInvalid(validateSegment(raw(sourceId = "other"), window()))
        assertInvalid(validateSegment(raw(segmentId = "other"), window()))
        assertInvalid(validateSegment(raw(segmentDuration = "9.9"), window()))
    }

    @Test
    fun `merges sources in stable source order`() {
        val later = validSegment(raw(event(id = "b"), sourceId = "later"), window(sourceOrder = 2, sourceId = "later"))
        val earlier = validSegment(raw(event(id = "a"), sourceId = "earlier"), window(sourceOrder = 1, sourceId = "earlier"))

        assertEquals(listOf("earlier", "later"), mergeSegments(listOf(later, earlier)).sources.map { it.window.sourceId })
    }

    @Test
    fun `merges segments of one source into one item and stably sorts absolute events`() {
        val second = validSegment(
            raw(
                event(id = "same-activity-right", start = "1", end = "2", continuesBefore = true),
                event(id = "later", start = "2", end = "3"),
                segmentId = "source-a-s02",
                segmentDuration = "6",
            ),
            window(
                segmentId = "source-a-s02",
                sourceStart = "4",
                sourceEnd = "10",
                segmentDuration = "6",
                coreStart = "1",
                coreEnd = "6",
            ),
        )
        val first = validSegment(
            raw(
                event(id = "same-activity-left", start = "4", end = "5", continuesAfter = true),
                event(id = "early", start = "1", end = "2"),
                segmentId = "source-a-s01",
                segmentDuration = "6",
            ),
            window(
                segmentId = "source-a-s01",
                sourceStart = "0",
                sourceEnd = "6",
                segmentDuration = "6",
                coreStart = "0",
                coreEnd = "5",
            ),
        )

        val merged = mergeSegments(listOf(second, first))

        assertEquals(1, merged.sources.size)
        assertEquals(
            listOf("early", "same-activity-left", "same-activity-right", "later"),
            merged.sources.single().events.map { it.event.eventId },
        )
        assertEquals(2, merged.sources.single().events.count { it.event.eventId.startsWith("same-activity") })
    }

    @Test
    fun `merge rejects inconsistent metadata for one source id`() {
        val first = validSegment(raw(), window(sourceOrder = 1))
        val wrongOrder = validSegment(raw(event(id = "other")), window(sourceOrder = 2))
        val wrongDuration = validSegment(raw(event(id = "other")), window(sourceDuration = "11"))

        listOf(wrongOrder, wrongDuration).forEach { inconsistent ->
            try {
                mergeSegments(listOf(first, inconsistent))
                fail("Expected inconsistent source metadata to fail")
            } catch (failure: IllegalArgumentException) {
                assertEquals("inconsistent_source_metadata", failure.message)
            }
        }
    }

    @Test
    fun `merge rejects duplicate event ids within or across sources`() {
        val sameSegment = validSegment(
            raw(event(id = "same", start = "1", end = "2"), event(id = "same", start = "3", end = "4")),
            window(),
        )
        val firstSource = validSegment(
            raw(event(id = "same"), sourceId = "first"),
            window(sourceOrder = 1, sourceId = "first"),
        )
        val secondSource = validSegment(
            raw(event(id = "same"), sourceId = "second"),
            window(sourceOrder = 2, sourceId = "second"),
        )
        val firstSegment = validSegment(
            raw(event(id = "same"), segmentId = "source-a-s01", segmentDuration = "5"),
            window(segmentId = "source-a-s01", sourceEnd = "5", segmentDuration = "5"),
        )
        val secondSegment = validSegment(
            raw(event(id = "same"), segmentId = "source-a-s02", segmentDuration = "5"),
            window(segmentId = "source-a-s02", sourceStart = "5", segmentDuration = "5"),
        )

        listOf(
            listOf(sameSegment),
            listOf(firstSource, secondSource),
            listOf(firstSegment, secondSegment),
        ).forEach { segments ->
            try {
                mergeSegments(segments)
                fail("Expected duplicate event ids to fail")
            } catch (failure: IllegalArgumentException) {
                assertEquals("duplicate_event_id", failure.message)
            }
        }
    }

    private fun validSegment(raw: SegmentUnderstanding, window: SourceWindow): ValidatedSegment =
        (validateSegment(raw, window) as ValidationResult.Valid).value

    private fun assertInvalid(result: ValidationResult<*>) {
        assertTrue("Expected validation failure", result is ValidationResult.Invalid)
    }

    private fun assertInvalidWithCode(result: ValidationResult<*>, code: String) {
        assertInvalid(result)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.code == code })
    }

    private fun window(
        sourceOrder: Int = 1,
        sourceId: String = "source-a",
        sourceDuration: String = "10",
        segmentId: String = "segment-a",
        sourceStart: String = "0",
        sourceEnd: String = "10",
        segmentDuration: String = "10",
        coreStart: String = "0",
        coreEnd: String = segmentDuration,
    ) = SourceWindow(
        sourceOrder = sourceOrder,
        sourceId = sourceId,
        sourceDuration = BigDecimal(sourceDuration),
        segmentId = segmentId,
        segmentSourceStart = BigDecimal(sourceStart),
        segmentSourceEnd = BigDecimal(sourceEnd),
        segmentDuration = BigDecimal(segmentDuration),
        reportingCoreStartInSegment = BigDecimal(coreStart),
        reportingCoreEndInSegment = BigDecimal(coreEnd),
    )

    private fun raw(
        vararg events: UnderstandingEvent,
        sourceId: String = "source-a",
        segmentId: String = "segment-a",
        segmentDuration: String = "10",
    ) = SegmentUnderstanding(
        sourceId,
        segmentId,
        BigDecimal(segmentDuration),
        if (events.isEmpty()) listOf(event()) else events.toList(),
    )

    private fun event(
        id: String = "event-a",
        start: String = "1",
        end: String = "2",
        description: String = "happy family",
        continuesBefore: Boolean = false,
        continuesAfter: Boolean = false,
    ) = UnderstandingEvent(
        eventId = id,
        startInSegment = BigDecimal(start),
        endInSegment = BigDecimal(end),
        continuesBefore = continuesBefore,
        continuesAfter = continuesAfter,
        description = description,
        audioDescription = null,
    )
}
