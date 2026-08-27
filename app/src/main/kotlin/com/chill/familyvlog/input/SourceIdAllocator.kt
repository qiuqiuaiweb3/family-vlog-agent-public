package com.chill.familyvlog.input

import android.net.Uri

data class SourceIdentity(
    val sourceOrder: Int,
    val sourceId: String,
)

fun sourceIdentityAt(index: Int): SourceIdentity {
    val sourceOrder = index + 1
    return SourceIdentity(sourceOrder, "video_${sourceOrder.toString().padStart(2, '0')}")
}

fun assignSourceIds(uris: List<Uri>): List<SelectedSource> = uris.mapIndexed { index, uri ->
    sourceIdentityAt(index).let { identity ->
        SelectedSource(identity.sourceOrder, identity.sourceId, uri)
    }
}
