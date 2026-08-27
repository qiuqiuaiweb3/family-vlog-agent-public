package com.chill.familyvlog.ui

import androidx.annotation.StringRes
import com.chill.familyvlog.R
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.subtitle.SubtitlePhase

internal data class StagePresentation(
    val currentStage: Int,
    val totalStages: Int,
    @param:StringRes val labelRes: Int,
)

internal fun RunPhase.toStagePresentation(): StagePresentation = when (this) {
    RunPhase.PREPARING -> StagePresentation(1, 5, R.string.phase_preparing)
    RunPhase.ANALYZING -> StagePresentation(2, 5, R.string.phase_analyzing)
    RunPhase.PLANNING -> StagePresentation(3, 5, R.string.phase_planning)
    RunPhase.RENDERING -> StagePresentation(4, 5, R.string.phase_rendering)
    RunPhase.SAVING -> StagePresentation(5, 5, R.string.phase_saving)
}

internal fun SubtitlePhase.toStagePresentation(): StagePresentation = when (this) {
    SubtitlePhase.TRANSCRIBING -> StagePresentation(1, 5, R.string.subtitle_phase_transcribing)
    SubtitlePhase.DOWNLOADING_MODEL -> StagePresentation(2, 5, R.string.subtitle_phase_downloading_model)
    SubtitlePhase.TRANSLATING -> StagePresentation(3, 5, R.string.subtitle_phase_translating)
    SubtitlePhase.RENDERING -> StagePresentation(4, 5, R.string.subtitle_phase_rendering)
    SubtitlePhase.SAVING -> StagePresentation(5, 5, R.string.subtitle_phase_saving)
}
