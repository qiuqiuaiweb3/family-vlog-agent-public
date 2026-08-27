package com.chill.familyvlog.contract

import java.math.BigDecimal

data class SourceWindow(
    val sourceOrder: Int,
    val sourceId: String,
    val sourceDuration: BigDecimal,
    val segmentId: String,
    val segmentSourceStart: BigDecimal,
    val segmentSourceEnd: BigDecimal,
    val segmentDuration: BigDecimal,
    val reportingCoreStartInSegment: BigDecimal = BigDecimal.ZERO,
    val reportingCoreEndInSegment: BigDecimal = segmentDuration,
)

data class SegmentUnderstanding(
    val sourceId: String,
    val segmentId: String,
    val segmentDuration: BigDecimal,
    val events: List<UnderstandingEvent>,
)

data class UnderstandingEvent(
    val eventId: String,
    val startInSegment: BigDecimal,
    val endInSegment: BigDecimal,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
    val description: String,
    val audioDescription: String?,
)

data class EditingResponse(val clips: List<EditSelection>)

data class EditSelection(
    val eventId: String,
    val storyRole: String,
    val selectionReason: String,
)

data class EditClip(
    val sourceId: String,
    val eventId: String,
    val start: BigDecimal,
    val end: BigDecimal,
    val storyRole: String,
    val selectionReason: String,
)

data class Diagnostic(val code: String, val message: String)
data class ValidationError(val code: String, val message: String)

sealed interface ValidationResult<out T> {
    data class Valid<T>(val value: T, val diagnostics: List<Diagnostic>) : ValidationResult<T>
    data class Invalid(val errors: List<ValidationError>) : ValidationResult<Nothing>
}

data class ValidatedEvent(
    val event: UnderstandingEvent,
    val start: BigDecimal,
    val end: BigDecimal,
    val executionEnd: BigDecimal,
)

data class ValidatedSegment(
    val window: SourceWindow,
    val events: List<ValidatedEvent>,
)

data class VideoUnderstanding(val sources: List<ValidatedSegment>)

data class ValidatedEditClip(
    val clip: EditClip,
    val executionEnd: BigDecimal,
)

data class ValidatedEditPlan(
    val clips: List<ValidatedEditClip>,
    val totalDuration: BigDecimal,
)
