package com.chill.familyvlog.contract

import java.math.BigDecimal
import java.math.RoundingMode

private val TERMINAL_TOLERANCE = BigDecimal("0.0005")

fun validateSegment(raw: SegmentUnderstanding, window: SourceWindow): ValidationResult<ValidatedSegment> {
    val errors = mutableListOf<ValidationError>()
    validateWindow(window, errors)
    if (raw.sourceId != window.sourceId) errors += error("source_id_mismatch")
    if (raw.segmentId != window.segmentId) errors += error("segment_id_mismatch")
    if (raw.segmentDuration.compareTo(window.segmentDuration) != 0) errors += error("segment_duration_mismatch")

    val events = raw.events.mapIndexedNotNull { index, event ->
        validateEvent(event, raw.segmentDuration, window, index, errors)
    }
    if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)

    val diagnostics = buildList {
        if (events.zipWithNext().any { (first, second) -> eventOrder(first, second) > 0 }) {
            add(Diagnostic("input_event_order", "Events were not in ascending execution order"))
        }
    }
    return ValidationResult.Valid(
        ValidatedSegment(window, events.sortedWith(::eventOrder)),
        diagnostics,
    )
}

fun mergeSegments(segments: List<ValidatedSegment>): VideoUnderstanding {
    val sources = segments
        .groupBy { it.window.sourceId }
        .values
        .map { sourceSegments -> mergeSourceSegments(sourceSegments) }
        .sortedBy { it.window.sourceOrder }
    val merged = VideoUnderstanding(sources)
    val eventIds = merged.sources.flatMap { segment -> segment.events.map { it.event.eventId } }
    require(eventIds.size == eventIds.toSet().size) { "duplicate_event_id" }
    return merged
}

private fun mergeSourceSegments(segments: List<ValidatedSegment>): ValidatedSegment {
    val firstWindow = segments.first().window
    require(
        segments.all { segment ->
            segment.window.sourceOrder == firstWindow.sourceOrder &&
                segment.window.sourceId == firstWindow.sourceId &&
                segment.window.sourceDuration.compareTo(firstWindow.sourceDuration) == 0
        },
    ) { "inconsistent_source_metadata" }
    val representative = segments.minWith(
        compareBy<ValidatedSegment> { it.window.segmentSourceStart }
            .thenBy { it.window.segmentSourceEnd }
            .thenBy { it.window.segmentId },
    )
    return ValidatedSegment(
        representative.window,
        segments.flatMap { it.events }.sortedWith(::eventOrder),
    )
}

private fun validateWindow(window: SourceWindow, errors: MutableList<ValidationError>) {
    if (window.sourceOrder < 1) errors += error("invalid_source_order")
    if (window.sourceDuration.signum() <= 0) errors += error("invalid_source_duration")
    if (window.segmentSourceStart.signum() < 0) errors += error("invalid_segment_start")
    if (window.segmentSourceEnd.compareTo(window.segmentSourceStart) <= 0) errors += error("invalid_segment_end")
    if (window.segmentSourceEnd.compareTo(window.sourceDuration) > 0) errors += error("segment_exceeds_source")
    if (window.segmentDuration.signum() <= 0) errors += error("invalid_segment_duration")
    if (window.reportingCoreStartInSegment.signum() < 0) errors += error("invalid_reporting_core_start")
    if (window.reportingCoreEndInSegment.compareTo(window.reportingCoreStartInSegment) <= 0) {
        errors += error("invalid_reporting_core_end")
    }
    if (window.reportingCoreEndInSegment.compareTo(window.segmentDuration) > 0) {
        errors += error("reporting_core_exceeds_segment")
    }
}

private fun validateEvent(
    event: UnderstandingEvent,
    segmentDuration: BigDecimal,
    window: SourceWindow,
    index: Int,
    errors: MutableList<ValidationError>,
): ValidatedEvent? {
    if (event.startInSegment.signum() < 0) {
        errors += error("negative_event_start", index)
        return null
    }
    if (event.endInSegment.compareTo(event.startInSegment) <= 0) {
        errors += error("invalid_event_range", index)
        return null
    }
    if (event.description.isBlank()) {
        errors += error("blank_description", index)
        return null
    }
    val absoluteStart = window.segmentSourceStart.add(event.startInSegment)
    val absoluteEnd = window.segmentSourceStart.add(event.endInSegment)
    val roundedSourceSpan = window.segmentSourceEnd
        .subtract(window.segmentSourceStart)
        .setScale(3, RoundingMode.HALF_UP)
    val executionEnd = when {
        event.endInSegment.compareTo(segmentDuration) <= 0 && absoluteEnd.compareTo(window.segmentSourceEnd) <= 0 -> absoluteEnd
        event.endInSegment.compareTo(roundedSourceSpan) == 0 &&
            absoluteEnd > window.segmentSourceEnd &&
            absoluteEnd.subtract(window.segmentSourceEnd).compareTo(TERMINAL_TOLERANCE) <= 0 -> window.segmentSourceEnd
        else -> {
            errors += error("event_exceeds_segment", index)
            return null
        }
    }
    val belongsToCore = if (event.continuesBefore || event.continuesAfter) {
        event.startInSegment >= window.reportingCoreStartInSegment &&
            event.endInSegment <= window.reportingCoreEndInSegment
    } else {
        val midpoint = event.startInSegment.add(event.endInSegment).divide(BigDecimal("2"))
        midpoint >= window.reportingCoreStartInSegment && midpoint < window.reportingCoreEndInSegment
    }
    if (!belongsToCore) {
        errors += error(
            if (event.continuesBefore || event.continuesAfter) {
                "continued_event_outside_reporting_core"
            } else {
                "complete_event_outside_reporting_core"
            },
            index,
        )
        return null
    }
    return ValidatedEvent(event, absoluteStart, absoluteEnd, executionEnd)
}

private fun eventOrder(first: ValidatedEvent, second: ValidatedEvent): Int =
    first.start.compareTo(second.start).takeIf { it != 0 } ?: first.executionEnd.compareTo(second.executionEnd)

private fun error(code: String, index: Int? = null): ValidationError =
    ValidationError(code, if (index == null) code else "$code at event $index")
