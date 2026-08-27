package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.output.PublicationReceipt

enum class SubtitleLanguage { CHINESE, ENGLISH }

data class TimedToken(
    val startUs: Long,
    val endUs: Long,
    val text: String,
)

data class TranscriptCue(
    val language: SubtitleLanguage,
    val tokens: List<TimedToken>,
)

data class BilingualCaptionCue(
    val startUs: Long,
    val endUs: Long,
    val chinese: String,
    val english: String,
)

data class SubtitleSource(
    val uri: Uri,
    val durationUs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val hasAudio: Boolean,
)

enum class SubtitlePhase { TRANSCRIBING, DOWNLOADING_MODEL, TRANSLATING, RENDERING, SAVING }

enum class SubtitleFailureCode {
    TRANSCRIPTION_FAILED,
    MODEL_DOWNLOAD_FAILED,
    TRANSLATION_FAILED,
    SUBTITLE_LAYOUT_FAILED,
    PRIVATE_STORAGE_FAILED,
    RENDER_FAILED,
    OUTPUT_INSPECTION_FAILED,
    PUBLISH_FAILED,
}

sealed interface SubtitleRunState {
    data object Idle : SubtitleRunState
    data class Active(val phase: SubtitlePhase) : SubtitleRunState
    data object NoSpeech : SubtitleRunState
    data object Succeeded : SubtitleRunState
    data class Failed(
        val phase: SubtitlePhase,
        val code: SubtitleFailureCode,
    ) : SubtitleRunState
    data object Cancelled : SubtitleRunState
}

class SubtitleException(
    val phase: SubtitlePhase,
    val code: SubtitleFailureCode,
    cause: Throwable? = null,
) : RuntimeException("${phase.name}:${code.name}", cause)

sealed interface SubtitleJobResult {
    data object NoSpeech : SubtitleJobResult
    data class Published(val receipt: PublicationReceipt) : SubtitleJobResult
}

internal fun SubtitleRunState.isActive(): Boolean = this is SubtitleRunState.Active
