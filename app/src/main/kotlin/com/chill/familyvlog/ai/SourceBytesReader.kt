package com.chill.familyvlog.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface SourceBytesReader {
    suspend fun read(uri: Uri, maxBytes: Int): SourceBytesReadResult
}

sealed interface SourceBytesReadResult {
    data class Fits(val bytes: ByteArray) : SourceBytesReadResult

    data object TooLarge : SourceBytesReadResult
}

class AndroidSourceBytesReader(
    private val contentResolver: ContentResolver,
) : SourceBytesReader {
    override suspend fun read(uri: Uri, maxBytes: Int): SourceBytesReadResult = withContext(Dispatchers.IO) {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("SOURCE_READ_FAILED")
        descriptor.use {
            if (descriptor.statSize > maxBytes.toLong()) {
                return@withContext SourceBytesReadResult.TooLarge
            }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                readBoundedSource(stream, maxBytes)
            }
        }
    }
}

internal suspend fun readBoundedSource(
    stream: InputStream,
    maxBytes: Int,
): SourceBytesReadResult {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE).coerceAtLeast(1))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes.toLong() + 1L
    while (remaining > 0L) {
        coroutineContext.ensureActive()
        val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) return SourceBytesReadResult.Fits(output.toByteArray())
        output.write(buffer, 0, count)
        remaining -= count.toLong()
    }
    return SourceBytesReadResult.TooLarge
}
