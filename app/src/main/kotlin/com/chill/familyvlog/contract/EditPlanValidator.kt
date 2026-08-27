package com.chill.familyvlog.contract

import java.math.BigDecimal

fun validatePlan(raw: EditingResponse, understanding: VideoUnderstanding): ValidationResult<ValidatedEditPlan> {
    val errors = mutableListOf<ValidationError>()
    if (raw.clips.isEmpty()) errors += ValidationError("empty_clips", "At least one clip is required")
    val eventsById = understanding.sources
        .flatMap { segment ->
            segment.events.map { event -> Triple(event.event.eventId, segment.window.sourceId, event) }
        }
        .groupBy { it.first }
    val clips = raw.clips.mapIndexedNotNull { index, selection ->
        val matches = eventsById[selection.eventId].orEmpty()
        if (matches.size != 1) {
            errors += ValidationError("unresolved_event_reference", "Clip $index does not identify one event")
            null
        } else {
            val match = matches.single()
            ValidatedEditClip(
                clip = EditClip(
                    sourceId = match.second,
                    eventId = selection.eventId,
                    start = match.third.start,
                    end = match.third.end,
                    storyRole = selection.storyRole,
                    selectionReason = selection.selectionReason,
                ),
                executionEnd = match.third.executionEnd,
            )
        }
    }
    if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)

    val diagnostics = mutableListOf<Diagnostic>()
    clips.groupBy { it.clip.sourceId to it.clip.eventId }
        .filterValues { it.size > 1 }
        .forEach { (reference, _) -> diagnostics += Diagnostic("duplicate_clip_reference", "Repeated clip reference $reference") }
    clips.groupBy { it.clip.sourceId }.forEach { (sourceId, sourceClips) ->
        if (sourceClips.zipWithNext().any { (first, second) -> first.clip.start > second.clip.start }) {
            diagnostics += Diagnostic("source_clip_order", "Clips for $sourceId are out of order")
        }
        if (sourceClips.indices.any { first ->
                (first + 1 until sourceClips.size).any { second ->
                    sourceClips[first].executionEnd > sourceClips[second].clip.start &&
                        sourceClips[second].executionEnd > sourceClips[first].clip.start
                }
            }
        ) {
            diagnostics += Diagnostic("source_clip_overlap", "Clips for $sourceId overlap")
        }
    }
    clips.filter { it.clip.let { clip ->
        eventsById[clip.eventId].orEmpty().single().third.event.let { it.continuesBefore || it.continuesAfter }
    } }.forEach { clip ->
        diagnostics += Diagnostic("continued_event", "Clip ${clip.clip.eventId} is a continuation")
    }
    val totalDuration = clips.fold(BigDecimal.ZERO) { total, clip ->
        total.add(clip.executionEnd.subtract(clip.clip.start))
    }
    return ValidationResult.Valid(ValidatedEditPlan(clips, totalDuration), diagnostics)
}
