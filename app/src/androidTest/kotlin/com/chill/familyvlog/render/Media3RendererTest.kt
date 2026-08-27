package com.chill.familyvlog.render

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3RendererTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun officialChannelMixerConfiguresOneThroughSixChannelsToStereoAndRejectsSeven() {
        (1..6).forEach { inputChannels ->
            val processor = buildAudioEffects(hasAudio = true).audioProcessors.single()
            val input = AudioFormat(48_000, inputChannels, C.ENCODING_PCM_16BIT)
            val configured = processor.configure(input)
            val effective = if (configured == AudioFormat.NOT_SET) input else configured
            assertEquals(2, effective.channelCount)
        }
        try {
            buildAudioEffects(hasAudio = true).audioProcessors.single()
                .configure(AudioFormat(48_000, 7, C.ENCODING_PCM_16BIT))
            fail("Expected unsupported audio format")
        } catch (_: UnhandledAudioFormatException) {
        }
    }

    @Test
    fun variableFrameRateNonZeroCutExportsOneBasicVideoTrack() = runBlocking {
        val fixture = FixtureMediaProvider.RENDER_LANDSCAPE_VFR
        val intervals = videoSampleIntervals(fixture)
        assertEquals(44, intervals.size)
        assertTrue(intervals.take(15).all { it in 60_000L..70_000L })
        assertTrue(intervals.drop(15).all { it in 30_000L..40_000L })
        val output = outputFile("vfr")
        try {
            val result = Media3Renderer(context).render(
                RenderSpec(
                    clips = listOf(clip(fixture, hasAudio = false, startUs = 500_000L, endUs = 2_000_000L)),
                    expectsAudio = false,
                    canvasAspectRatio = 16f / 9f,
                ),
                output,
            )

            assertTrue(output.length() > 0L)
            val tracks = inspect(output)
            val video = tracks.single { it.mime.startsWith("video/") }
            assertEquals(result.videoMimeType, video.mime)
            assertTrue(video.hasSample)
            assertTrue(video.durationUs in 1_000_000L..1_600_000L)
            assertAspect(video, 16.0 / 9.0)
            assertFalse(tracks.any { it.mime.startsWith("audio/") })
        } finally {
            output.delete()
        }
    }

    @Test
    fun executionFirstCanvasAndMixedAudioWorkInEitherOrder() = runBlocking {
        val landscapeOutput = outputFile("rotated-first")
        val portraitOutput = outputFile("portrait-first")
        try {
            val rotated = clip(FixtureMediaProvider.RENDER_ROTATED_SILENT, hasAudio = false)
            val portrait = clip(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO, hasAudio = true)

            Media3Renderer(context).render(
                RenderSpec(listOf(rotated, portrait), expectsAudio = true, canvasAspectRatio = 16f / 9f),
                landscapeOutput,
            )
            Media3Renderer(context).render(
                RenderSpec(listOf(portrait, rotated), expectsAudio = true, canvasAspectRatio = 9f / 16f),
                portraitOutput,
            )

            val landscapeTracks = inspect(landscapeOutput)
            val portraitTracks = inspect(portraitOutput)
            assertAspect(landscapeTracks.single { it.mime.startsWith("video/") }, 16.0 / 9.0)
            assertAspect(portraitTracks.single { it.mime.startsWith("video/") }, 9.0 / 16.0)
            listOf(landscapeTracks, portraitTracks).forEach { tracks ->
                assertEquals(1, tracks.count { it.mime.startsWith("video/") })
                val audio = tracks.single { it.mime.startsWith("audio/") }
                assertTrue(audio.hasSample)
                assertEquals(2, audio.channelCount)
                assertTrue(tracks.maxOf(Track::durationUs) in 1_500_000L..2_500_000L)
            }
        } finally {
            landscapeOutput.delete()
            portraitOutput.delete()
        }
    }

    @Test
    fun hlgInputUsesOpenGlToneMappingAndProducesSdrMetadata() = runBlocking {
        val output = outputFile("hlg-sdr")
        try {
            val result = Media3Renderer(context).render(
                RenderSpec(
                    clips = listOf(clip(FixtureMediaProvider.RENDER_HEVC_HLG, hasAudio = false)),
                    expectsAudio = false,
                    canvasAspectRatio = 16f / 9f,
                ),
                output,
            )

            val tracks = inspect(output)
            val video = tracks.single { it.mime.startsWith("video/") }
            assertEquals(result.videoMimeType, video.mime)
            assertTrue(video.hasSample)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, video.colorTransfer)
            assertAspect(video, 16.0 / 9.0)
        } finally {
            output.delete()
        }
    }

    @Test
    fun sequenceLongerThanFortyFiveSecondsIsNotTruncated() = runBlocking {
        val output = outputFile("long-complete")
        try {
            val repeated = clip(FixtureMediaProvider.RENDER_LANDSCAPE_SILENT, hasAudio = false)
            withTimeout(300_000L) {
                Media3Renderer(context).render(
                    RenderSpec(List(46) { repeated.copy() }, expectsAudio = false, canvasAspectRatio = 16f / 9f),
                    output,
                )
            }

            val video = inspect(output).single { it.mime.startsWith("video/") }
            assertTrue(video.hasSample)
            assertTrue(video.durationUs in 45_500_000L..46_500_000L)
            assertTrue(video.lastSampleTimeUs in 45_400_000L..46_500_000L)
        } finally {
            output.delete()
        }
    }

    @Test
    fun concurrentCallFailsThenCancelledRendererCanBeReusedWithoutLateCancellation() = runBlocking {
        val renderer = Media3Renderer(context)
        val longOutput = outputFile("long-cancel")
        val concurrentOutput = outputFile("concurrent")
        val reusedOutput = outputFile("reuse")
        val repeated = clip(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO, hasAudio = true)
        val first = async(Dispatchers.Default) {
            renderer.render(
                RenderSpec(List(120) { repeated.copy() }, expectsAudio = true, canvasAspectRatio = 9f / 16f),
                longOutput,
            )
        }
        try {
            withTimeout(10_000L) {
                while (!longOutput.exists() && !first.isCompleted) delay(20L)
                if (first.isCompleted) first.await()
            }
            try {
                renderer.render(
                    RenderSpec(listOf(repeated), expectsAudio = true, canvasAspectRatio = 9f / 16f),
                    concurrentOutput,
                )
                fail("Expected concurrent export failure")
            } catch (failure: PipelineException) {
                assertEquals(RunPhase.RENDERING, failure.phase)
                assertEquals(RunFailureCode.RENDER_FAILED, failure.code)
            }

            first.cancelAndJoin()
            assertTrue(first.isCancelled)

            renderer.render(
                RenderSpec(listOf(repeated), expectsAudio = true, canvasAspectRatio = 9f / 16f),
                reusedOutput,
            )
            assertTrue(reusedOutput.length() > 0L)
        } finally {
            first.cancelAndJoin()
            longOutput.delete()
            concurrentOutput.delete()
            reusedOutput.delete()
        }
    }

    private fun clip(
        name: String,
        hasAudio: Boolean,
        startUs: Long = 0L,
        endUs: Long = 1_000_000L,
    ) = RenderClip(
        uri = FixtureMediaProvider.uriFor(name),
        startUs = startUs,
        endUs = endUs,
        hasAudio = hasAudio,
    )

    private fun outputFile(label: String) =
        File(context.cacheDir, "media3-render-$label-${UUID.randomUUID()}.mp4")

    private fun assertAspect(track: Track, expected: Double) {
        val error = abs(track.displayWidth.toDouble() / track.displayHeight - expected)
        assertTrue(error <= 1.0 / track.displayHeight)
    }

    private fun videoSampleIntervals(name: String): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, FixtureMediaProvider.uriFor(name), emptyMap())
            val videoTrack = (0 until extractor.trackCount).single { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                    .startsWith("video/")
            }
            extractor.selectTrack(videoTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val format = extractor.getTrackFormat(videoTrack)
            val input = format.sampleBuffer()
            val intervals = mutableListOf<Long>()
            var previous: Long? = null
            while (true) {
                input.clear()
                if (extractor.readSampleData(input, 0) < 0) break
                val current = extractor.sampleTime
                previous?.let { previousTimeUs ->
                    assertTrue(current > previousTimeUs)
                    intervals += current - previousTimeUs
                }
                previous = current
                if (!extractor.advance()) break
            }
            return intervals
        } finally {
            extractor.release()
        }
    }

    private fun inspect(file: File): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: 0
                val height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0
                val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
                val durationUs = format.longOrNull(MediaFormat.KEY_DURATION) ?: 0L
                extractor.selectTrack(index)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val input = format.sampleBuffer()
                var hasSample = false
                var lastSampleTimeUs = Long.MIN_VALUE
                while (true) {
                    input.clear()
                    if (extractor.readSampleData(input, 0) < 0) break
                    assertEquals(index, extractor.sampleTrackIndex)
                    hasSample = true
                    lastSampleTimeUs = extractor.sampleTime
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(index)
                val swap = Math.floorMod(rotation, 180) != 0
                Track(
                    mime = mime,
                    displayWidth = if (swap) height else width,
                    displayHeight = if (swap) width else height,
                    durationUs = durationUs,
                    hasSample = hasSample,
                    lastSampleTimeUs = if (hasSample) lastSampleTimeUs else -1L,
                    colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                    channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                )
            }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null

    private fun MediaFormat.sampleBuffer(): ByteBuffer = ByteBuffer.allocateDirect(
        (intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_SAMPLE_BUFFER_BYTES).coerceAtLeast(1),
    )

    private data class Track(
        val mime: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val durationUs: Long,
        val hasSample: Boolean,
        val lastSampleTimeUs: Long,
        val colorTransfer: Int?,
        val channelCount: Int?,
    )

    private companion object {
        const val DEFAULT_SAMPLE_BUFFER_BYTES = 4 * 1_024 * 1_024
    }
}
