package com.chill.familyvlog.output

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class OutputInspection(
    val nonEmpty: Boolean,
    val parseable: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

fun interface OutputInspector {
    suspend fun inspect(file: File): OutputInspection
}

class MediaExtractorOutputInspector internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
) : OutputInspector {
    constructor() : this(Dispatchers.IO)

    override suspend fun inspect(file: File): OutputInspection = withContext(ioDispatcher) {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val nonEmpty = file.isFile && file.length() > 0L
        if (!nonEmpty) {
            callerContext.ensureActive()
            return@withContext OutputInspection(false, false, false, false)
        }

        val extractor = MediaExtractor()
        val inspection = try {
            FileInputStream(file).use { input -> extractor.setDataSource(input.fd) }
            var hasVideo = false
            var hasAudio = false
            for (index in 0 until extractor.trackCount) {
                callerContext.ensureActive()
                when {
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true ->
                        hasVideo = true
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true ->
                        hasAudio = true
                }
            }
            OutputInspection(true, true, hasVideo, hasAudio)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OutputInspection(true, false, false, false)
        } finally {
            extractor.release()
        }
        callerContext.ensureActive()
        inspection
    }
}
