package com.chill.familyvlog.media

import android.media.MediaFormat

internal fun selectAndroidMediaTracks(formats: List<MediaFormat>): LogicalMediaTrackSelection =
    selectLogicalMediaTracks(
        formats.mapIndexedNotNull { index, format ->
            val mimeType = format.valueOrNull { getString(MediaFormat.KEY_MIME) }
                ?: return@mapIndexedNotNull null
            MediaTrackDescriptor(
                index = index,
                mimeType = mimeType,
                trackId = format.integerValueOrNull(MediaFormat.KEY_TRACK_ID),
            )
        },
    )

internal fun MediaFormat.integerValueOrNull(key: String): Int? = valueOrNull { getInteger(key) }

private fun <T> MediaFormat.valueOrNull(read: MediaFormat.() -> T): T? = try {
    read()
} catch (_: Exception) {
    null
}
