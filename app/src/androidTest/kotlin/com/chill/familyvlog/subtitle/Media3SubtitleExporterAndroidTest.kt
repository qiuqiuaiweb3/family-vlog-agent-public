package com.chill.familyvlog.subtitle

import android.content.ContentResolver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3SubtitleExporterAndroidTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun syntheticAssExportsSeparateCommittedMp4WithOriginalTechnicalTracks() = runBlocking {
        val inputUri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO)
        val inputTracks = inspect(inputUri)
        val inputVideo = inputTracks.single { it.mime.startsWith("video/") }
        assertEquals(1, inputTracks.count { it.mime.startsWith("audio/") })
        assertTrue(inputVideo.hasSample)
        assertEquals(576, inputVideo.displayWidth)
        assertEquals(1_024, inputVideo.displayHeight)
        assertEquals(0, Math.floorMod(inputVideo.rotationDegrees, 360))

        val sourceDurationUs = requireNotNull(inputTracks.mapNotNull(Track::durationUs).maxOrNull())
        assertTrue(sourceDurationUs in 950_000L..1_050_000L)
        val source = SubtitleSource(
            uri = inputUri,
            durationUs = sourceDurationUs,
            videoWidth = inputVideo.displayWidth,
            videoHeight = inputVideo.displayHeight,
            hasAudio = true,
        )
        val assDocument = buildAssSubtitleDocument(
            videoWidth = source.videoWidth,
            videoHeight = source.videoHeight,
            cues = listOf(
                BilingualCaptionCue(
                    startUs = 100_000L,
                    endUs = 900_000L,
                    chinese = "合成字幕",
                    english = "synthetic caption",
                ),
            ),
        )

        var savingCalls = 0
        var createdUri: Uri? = null
        try {
            val receipt = withTimeout(180_000L) {
                Media3SubtitleExporter(context).export(
                    source = source,
                    assDocument = assDocument,
                    onSaving = { savingCalls += 1 },
                    onCommitted = { committed ->
                        check(createdUri == null)
                        createdUri = committed.uri
                    },
                )
            }

            assertEquals(1, savingCalls)
            assertEquals(createdUri, receipt.uri)
            assertNotEquals(inputUri, receipt.uri)
            assertCommittedAndNonEmpty(receipt.uri)

            val outputTracks = inspect(receipt.uri)
            val outputVideo = outputTracks.single { it.mime.startsWith("video/") }
            val outputAudio = outputTracks.single { it.mime.startsWith("audio/") }
            assertEquals("video/avc", outputVideo.mime)
            assertTrue(outputVideo.hasSample)
            assertTrue(outputAudio.hasSample)
            assertEquals(source.videoWidth, outputVideo.displayWidth)
            assertEquals(source.videoHeight, outputVideo.displayHeight)
            assertEquals(0, Math.floorMod(outputVideo.rotationDegrees, 90))

            val outputDurationUs = requireNotNull(outputTracks.mapNotNull(Track::durationUs).maxOrNull())
            assertTrue(abs(outputDurationUs - source.durationUs) <= 250_000L)
        } finally {
            createdUri?.let { exactUri ->
                check(exactUri.scheme == ContentResolver.SCHEME_CONTENT)
                check(exactUri.authority == MediaStore.AUTHORITY)
                assertEquals(1, context.contentResolver.delete(exactUri, null, null))
            }
        }
    }

    private fun assertCommittedAndNonEmpty(uri: Uri) {
        requireNotNull(
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            ),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertTrue(cursor.getLong(1) > 0L)
        }
    }

    private fun inspect(uri: Uri): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, emptyMap())
            return (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                extractor.selectTrack(index)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val input = ByteBuffer.allocateDirect(
                    (format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_SAMPLE_BUFFER_BYTES)
                        .coerceAtLeast(1),
                )
                input.clear()
                val hasSample = extractor.readSampleData(input, 0) >= 0 &&
                    extractor.sampleTrackIndex == index
                extractor.unselectTrack(index)
                Track(
                    mime = format.stringOrNull(MediaFormat.KEY_MIME).orEmpty(),
                    width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: 0,
                    height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0,
                    rotationDegrees = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
                    durationUs = format.longOrNull(MediaFormat.KEY_DURATION),
                    hasSample = hasSample,
                )
            }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private companion object {
        const val DEFAULT_SAMPLE_BUFFER_BYTES = 4 * 1_024 * 1_024
    }

    private data class Track(
        val mime: String,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val durationUs: Long?,
        val hasSample: Boolean,
    ) {
        val displayWidth: Int
            get() = if (Math.floorMod(rotationDegrees, 180) == 90) height else width

        val displayHeight: Int
            get() = if (Math.floorMod(rotationDegrees, 180) == 90) width else height
    }
}
