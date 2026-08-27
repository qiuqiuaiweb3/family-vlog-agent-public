package com.chill.familyvlog.ui

import com.chill.familyvlog.R
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.subtitle.SubtitlePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class VlogUiMappingsTest {
    @Test
    fun runPhaseMappingsAreExplicitAndComplete() {
        val expected = mapOf(
            RunPhase.PREPARING to StagePresentation(1, 5, R.string.phase_preparing),
            RunPhase.ANALYZING to StagePresentation(2, 5, R.string.phase_analyzing),
            RunPhase.PLANNING to StagePresentation(3, 5, R.string.phase_planning),
            RunPhase.RENDERING to StagePresentation(4, 5, R.string.phase_rendering),
            RunPhase.SAVING to StagePresentation(5, 5, R.string.phase_saving),
        )

        assertEquals(RunPhase.entries.toSet(), expected.keys)
        RunPhase.entries.forEach { phase ->
            assertEquals(expected.getValue(phase), phase.toStagePresentation())
        }
    }

    @Test
    fun subtitlePhaseMappingsAreExplicitAndComplete() {
        val expected = mapOf(
            SubtitlePhase.TRANSCRIBING to StagePresentation(1, 5, R.string.subtitle_phase_transcribing),
            SubtitlePhase.DOWNLOADING_MODEL to StagePresentation(2, 5, R.string.subtitle_phase_downloading_model),
            SubtitlePhase.TRANSLATING to StagePresentation(3, 5, R.string.subtitle_phase_translating),
            SubtitlePhase.RENDERING to StagePresentation(4, 5, R.string.subtitle_phase_rendering),
            SubtitlePhase.SAVING to StagePresentation(5, 5, R.string.subtitle_phase_saving),
        )

        assertEquals(SubtitlePhase.entries.toSet(), expected.keys)
        SubtitlePhase.entries.forEach { phase ->
            assertEquals(expected.getValue(phase), phase.toStagePresentation())
        }
    }
}
