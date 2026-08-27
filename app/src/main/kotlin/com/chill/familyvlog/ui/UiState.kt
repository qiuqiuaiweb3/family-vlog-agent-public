package com.chill.familyvlog.ui

import android.net.Uri
import com.chill.familyvlog.input.RejectionReason
import com.chill.familyvlog.pipeline.RunState
import com.chill.familyvlog.subtitle.SubtitleRunState
import com.chill.familyvlog.subtitle.isActive

enum class SetupError {
    FIREBASE_NOT_CONFIGURED,
}

data class UiState(
    val disclosureConfirmed: Boolean = false,
    val selectedSourceIds: List<String> = emptyList(),
    val runState: RunState = RunState.Idle,
    val runCancelRequested: Boolean = false,
    val setupError: SetupError? = null,
    val inputRejection: RejectionReason? = null,
    val finalUri: Uri? = null,
    val subtitleRunState: SubtitleRunState = SubtitleRunState.Idle,
    val subtitleCancelRequested: Boolean = false,
    val subtitledUri: Uri? = null,
) {
    val canSelectVideos: Boolean
        get() = disclosureConfirmed && runState == RunState.Idle

    val canCreateVlog: Boolean
        get() = disclosureConfirmed &&
            runState == RunState.Idle &&
            selectedSourceIds.isNotEmpty() &&
            setupError == null

    val canAddSubtitles: Boolean
        get() = runState == RunState.Succeeded &&
            finalUri != null &&
            !subtitleRunState.isActive() &&
            subtitleRunState != SubtitleRunState.Succeeded
}

internal fun RunState.isTerminal(): Boolean =
    this is RunState.Succeeded || this is RunState.Failed || this is RunState.Cancelled
