package com.chill.familyvlog.render

import android.net.Uri

data class RenderClip(
    val uri: Uri,
    val startUs: Long,
    val endUs: Long,
    val hasAudio: Boolean,
)

data class RenderSpec(
    val clips: List<RenderClip>,
    val expectsAudio: Boolean,
    val canvasAspectRatio: Float,
)
