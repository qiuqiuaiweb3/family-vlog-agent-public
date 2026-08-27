package com.chill.familyvlog.input

import android.net.Uri

data class SelectedSource(
    val sourceOrder: Int,
    val sourceId: String,
    val uri: Uri,
)
