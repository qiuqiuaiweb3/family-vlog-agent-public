package com.chill.familyvlog.subtitle

import android.util.Log

internal const val SUBTITLE_DIAGNOSTIC_TAG = "FamilyVlogSubtitleDiag"

internal enum class SubtitleDiagnosticOperation(val value: String) {
    LANGUAGE_MAPPING("recognition_language"),
    TOKEN_VALIDATION("recognition_tokens"),
    SUBTITLE_JOB("subtitle_job"),
}

internal enum class SubtitleTimestampRule(val value: String) {
    NOT_APPLICABLE("not_applicable"),
    TOKEN_COUNT_MATCH("token_count_match"),
    FINITE_NONNEGATIVE("finite_nonnegative"),
    STRICTLY_INCREASING("strictly_increasing"),
    WITHIN_VAD_SEGMENT("within_vad_segment"),
    CUES_NONOVERLAP("cues_nonoverlap"),
}

internal fun normalizeDiagnosticLanguage(language: String?): String {
    val normalized = language?.lowercase()?.trim()?.removeSurrounding("<|", "|>")
    return when (normalized) {
        "zh", "zh-cn", "cmn", "en", "en-us", "en-gb", "yue", "ja", "ko" -> normalized
        null, "" -> "unknown"
        else -> "other"
    }
}

internal fun subtitleDiagnostic(
    operation: SubtitleDiagnosticOperation,
    failure: Throwable,
    language: String? = null,
    tokenCount: Int = -1,
    timestampCount: Int = -1,
    timestampRule: SubtitleTimestampRule = SubtitleTimestampRule.NOT_APPLICABLE,
): String {
    val subtitleFailure = failure as? SubtitleException
    return listOf(
        "phase=${subtitleFailure?.phase?.name ?: SubtitlePhase.TRANSCRIBING.name}",
        "code=${subtitleFailure?.code?.name ?: SubtitleFailureCode.TRANSCRIPTION_FAILED.name}",
        "operation=${operation.value}",
        "exception_type=${failure.safeTypeName()}",
        "cause_type=${failure.cause?.safeTypeName() ?: "none"}",
        "lang=${normalizeDiagnosticLanguage(language)}",
        "token_count=$tokenCount",
        "timestamp_count=$timestampCount",
        "timestamp_rule=${timestampRule.value}",
    ).joinToString(" ")
}

internal fun logSubtitleDiagnostic(message: String) {
    try {
        Log.i(SUBTITLE_DIAGNOSTIC_TAG, message)
    } catch (_: RuntimeException) {
        // Local JVM tests use the Android Log stub; diagnostics must not replace the real failure.
    }
}

private fun Throwable.safeTypeName(): String = javaClass.simpleName.ifBlank { "unknown" }
