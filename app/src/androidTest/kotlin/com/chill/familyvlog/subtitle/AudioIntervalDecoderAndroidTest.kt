package com.chill.familyvlog.subtitle

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import com.chill.familyvlog.render.RenderClip
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AudioIntervalDecoderAndroidTest {
    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaStoreSourceQueuesEveryReadableSampleAndStopsAtHalfOpenEnd() = runBlocking {
        val sourceUri = publishAudioFixture()
        try {
            val fullDurationUs = 1_000_000L
            val allSamples = enumerateCompressedAudioSamples(sourceUri, fullDurationUs)
            val allQueuedSamples = mutableListOf<ExtractorSample>()

            withTimeout(60_000L) {
                AndroidAudioIntervalDecoder(targetContext, allQueuedSamples::add).decode(
                    RenderClip(sourceUri, 0L, fullDurationUs, hasAudio = true),
                ) { }
            }

            assertTrue(allSamples.size > 2)
            assertEquals(allSamples, allQueuedSamples)

            val halfOpenEndUs = allSamples[allSamples.size / 2].presentationTimeUs
            val samplesBeforeEnd = allSamples.takeWhile {
                it.presentationTimeUs < halfOpenEndUs
            }
            val queuedBeforeEnd = mutableListOf<ExtractorSample>()

            withTimeout(60_000L) {
                AndroidAudioIntervalDecoder(targetContext, queuedBeforeEnd::add).decode(
                    RenderClip(sourceUri, 0L, halfOpenEndUs, hasAudio = true),
                ) { }
            }

            assertTrue(samplesBeforeEnd.isNotEmpty())
            assertTrue(samplesBeforeEnd.size < allSamples.size)
            assertEquals(samplesBeforeEnd, queuedBeforeEnd)
        } finally {
            targetContext.contentResolver.delete(sourceUri, null, null)
        }
    }

    @Test
    fun selectedHalfSecondBecomesExactlyMono16kPcmSamples() = runBlocking {
        val decoder = AndroidAudioIntervalDecoder(targetContext)
        var sampleCount = 0

        decoder.decode(
            RenderClip(
                uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
                startUs = 250_000L,
                endUs = 750_000L,
                hasAudio = true,
            ),
        ) { samples ->
            sampleCount += samples.size
        }

        assertEquals(8_000, sampleCount)
    }

    @Test
    fun fractionalMicrosecondBoundaryIsRoundedUpOnThe16kGrid() = runBlocking {
        val decoder = AndroidAudioIntervalDecoder(targetContext)
        var sampleCount = 0

        decoder.decode(
            RenderClip(
                uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
                startUs = 250_000L,
                endUs = 750_013L,
                hasAudio = true,
            ),
        ) { samples ->
            sampleCount += samples.size
        }

        assertEquals(8_001, sampleCount)
    }

    @Test
    fun cancellationReleasesDecoderAndTheSameInstanceCanDecodeAgain() = runBlocking {
        val decoder = AndroidAudioIntervalDecoder(targetContext)
        val firstOutput = CompletableDeferred<Unit>()
        val cancelled = launch {
            decoder.decode(
                RenderClip(
                    uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
                    startUs = 0L,
                    endUs = 750_000L,
                    hasAudio = true,
                ),
            ) {
                firstOutput.complete(Unit)
                awaitCancellation()
            }
        }

        withTimeout(60_000L) { firstOutput.await() }
        withTimeout(60_000L) { cancelled.cancelAndJoin() }

        var sampleCount = 0
        withTimeout(60_000L) {
            decoder.decode(
                RenderClip(
                    uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
                    startUs = 250_000L,
                    endUs = 350_000L,
                    hasAudio = true,
                ),
            ) { samples ->
                sampleCount += samples.size
            }
        }
        assertEquals(1_600, sampleCount)
    }

    private fun publishAudioFixture(): Uri {
        val resolver = targetContext.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "decoder-test-${UUID.randomUUID()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        try {
            requireNotNull(
                resolver.openInputStream(
                    FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
                ),
            ).use { source ->
                requireNotNull(resolver.openOutputStream(uri, "w")).use { target ->
                    source.copyTo(target)
                }
            }
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            check(updated == 1)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun enumerateCompressedAudioSamples(uri: Uri, endUs: Long): List<ExtractorSample> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(targetContext, uri, null)
            val audioTrack = (0 until extractor.trackCount).single { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(audioTrack)
            val input = ByteBuffer.allocateDirect(
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1),
            )
            extractor.selectTrack(audioTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return buildList {
                while (true) {
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) break
                    val sample = ExtractorSample(size, extractor.sampleTime)
                    if (sample.presentationTimeUs >= endUs) break
                    add(sample)
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }
}
