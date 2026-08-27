package com.chill.familyvlog.ai

import com.chill.familyvlog.input.ProbeResult
import java.util.Locale

private val supportedFirebaseVideoMimeTypes = setOf(
    "video/x-flv",
    "video/quicktime",
    "video/mpeg",
    "video/mpegps",
    "video/mpg",
    "video/mp4",
    "video/webm",
    "video/wmv",
    "video/3gpp",
)

fun resolveFirebaseInlineVideoMime(probe: ProbeResult): String {
    val normalized = normalizedFirebaseInlineVideoMime(probe)
    if (normalized == null) {
        val hasVerifiedMime = probe.containerMimeType
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()
            .isNotEmpty()
        throw AiFailureException(
            if (hasVerifiedMime) {
                AiFailureCode.INLINE_VIDEO_MIME_UNSUPPORTED
            } else {
                AiFailureCode.INLINE_VIDEO_MIME_UNKNOWN
            },
        )
    }
    return normalized
}

fun normalizedFirebaseInlineVideoMime(probe: ProbeResult): String? {
    val normalized = probe.containerMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return normalized.takeIf(supportedFirebaseVideoMimeTypes::contains)
}
