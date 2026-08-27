package com.chill.familyvlog.pipeline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.ai.AiRawResult
import com.chill.familyvlog.ai.AndroidSourceBytesReader
import com.chill.familyvlog.ai.EditingRequestFactory
import com.chill.familyvlog.ai.InlineRequestBudget
import com.chill.familyvlog.ai.ModelPart
import com.chill.familyvlog.ai.ModelRequest
import com.chill.familyvlog.ai.OneShotModelClient
import com.chill.familyvlog.ai.PromptRepository
import com.chill.familyvlog.ai.PromptSource
import com.chill.familyvlog.ai.SourceBytesReadResult
import com.chill.familyvlog.ai.SourceBytesReader
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.analysis.AndroidSourceMetadataReader
import com.chill.familyvlog.analysis.SourceMetadataReader
import com.chill.familyvlog.input.AndroidMediaProbe
import com.chill.familyvlog.input.FixtureMediaProvider
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.render.Media3Renderer
import com.chill.familyvlog.render.buildRenderSpec
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAnalysisMediaAdapterTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun syncAlignedRemux_isParseableAndDeletesItsTemporaryFileOnClose() = runBlocking {
        val uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO)
        val durationUs = requireNotNull(AndroidMediaProbe(context).inspect(uri).durationUs)
        val adapter = AndroidAnalysisMediaAdapter(context)

        val syncStarts = adapter.syncSampleStarts(uri)
        val artifact = adapter.remux(uri, syncStarts.first(), durationUs) as FileAnalysisMediaArtifact
        val result = artifact.read(Int.MAX_VALUE) as SourceBytesReadResult.Fits
        val copy = File.createTempFile("analysis-remux-copy-", ".mp4", context.cacheDir)
        copy.writeBytes(result.bytes)

        try {
            val inspection = inspect(copy)
            assertEquals(0L, syncStarts.first())
            assertTrue(syncStarts.zipWithNext().all { (first, second) -> second > first })
            assertTrue(inspection.hasVideo)
            assertTrue(inspection.hasAudio)
            assertTrue(inspection.videoSamples > 0)
            assertTrue(artifact.file.isFile)
        } finally {
            artifact.close()
            assertFalse(artifact.file.exists())
            copy.delete()
        }
    }

    @Test
    fun vp9WebmRemux_preservesACompatibleContainerWithoutEncoding() = runBlocking {
        val uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.VP9_WEBM)
        val durationUs = requireNotNull(AndroidMediaProbe(context).inspect(uri).durationUs)
        val adapter = AndroidAnalysisMediaAdapter(context)

        val artifact = adapter.remux(uri, 0L, durationUs) as FileAnalysisMediaArtifact

        try {
            assertEquals("video/webm", artifact.mimeType)
            assertEquals(setOf("video/x-vnd.on2.vp9"), inspect(artifact.file).trackMimeTypes)
        } finally {
            artifact.close()
            assertFalse(artifact.file.exists())
        }
    }

    @Test
    fun approximately31MbVp9Webm_usesProductionBoundariesWithoutProxyEncoding() = runBlocking {
        val sourceFile = File.createTempFile("analysis-31mb-vp9-", ".webm", context.cacheDir)
        repeatVp9WebmUntilApproximate31Mb(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.VP9_WEBM),
            sourceFile,
        )
        val uri = Uri.fromFile(sourceFile)

        try {
            assertTrue(sourceFile.length() in 30_500_000L..31_500_000L)
            val probe = AndroidMediaProbe(context).inspect(uri)
            assertEquals("video/webm", probe.containerMimeType)
            val delegateReader = AndroidSourceBytesReader(context.contentResolver)
            var directReadCalls = 0
            var directReadResult: SourceBytesReadResult? = null
            val sourceReader = SourceBytesReader { sourceUri, maxBytes ->
                directReadCalls += 1
                delegateReader.read(sourceUri, maxBytes).also { directReadResult = it }
            }
            val adapter = CountingAnalysisMediaAdapter(AndroidAnalysisMediaAdapter(context))
            val requestFactory = requestFactory()
            val consumed = mutableListOf<PreparedAnalysisInput>()
            RequestBoundaryAnalysisInputProcessor(
                sourceBytesReader = sourceReader,
                sourceMetadataReader = AndroidSourceMetadataReader(context),
                requestFactory = requestFactory,
                mediaAdapter = adapter,
            ).process(SelectedSource(1, "source", uri), probe) { input ->
                input.requireWithinRequestBudget(requestFactory)
                consumed += input
            }

            assertEquals(1, directReadCalls)
            assertEquals(SourceBytesReadResult.TooLarge, directReadResult)
            assertTrue(consumed.size > 1)
            assertTrue(consumed.all { it.mimeType == "video/webm" })
            assertTrue(consumed.all {
                requestFactory.estimateTotalBytes(
                    it.window,
                    it.sourceMetadata,
                    it.bytes,
                    it.mimeType,
                ) < InlineRequestBudget.REQUEST_LIMIT_BYTES
            })
            assertEquals(0L, consumed.first().window.segmentSourceStart.movePointRight(6).longValueExact())
            consumed.zipWithNext().forEach { (first, second) ->
                assertTrue(first.window.segmentSourceEnd >= second.window.segmentSourceStart)
                assertEquals(
                    first.window.segmentSourceStart.add(first.window.reportingCoreEndInSegment),
                    second.window.segmentSourceStart.add(second.window.reportingCoreStartInSegment),
                )
            }
            assertEquals(
                requireNotNull(probe.durationUs),
                consumed.last().window.segmentSourceEnd.movePointRight(6).longValueExact(),
            )
            assertTrue(adapter.remuxCalls > consumed.size)
            assertEquals(0, adapter.proxyCalls)
        } finally {
            assertTrue(sourceFile.delete())
        }
    }

    @Test
    fun approximately31MbAndSecondSource_produceTwoJsonAndOneTechnicalExport() = runBlocking {
        val sourceFile = File.createTempFile("analysis-stage-31mb-vp9-", ".webm", context.cacheDir)
        val secondFile = File.createTempFile("analysis-stage-second-", ".mp4", context.cacheDir)
        val output = File.createTempFile("family-vlog-stage-export-", ".mp4", context.cacheDir)
        output.delete()
        repeatVp9WebmUntilApproximate31Mb(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.VP9_WEBM),
            sourceFile,
        )
        context.contentResolver.openInputStream(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_PORTRAIT_AUDIO),
        )!!.use { input ->
            secondFile.outputStream().use(input::copyTo)
        }
        val first = SelectedSource(1, "video_01", Uri.fromFile(sourceFile))
        val second = SelectedSource(2, "video_02", Uri.fromFile(secondFile))
        val sources = listOf(first, second)
        val mediaProbe = AndroidMediaProbe(context)
        val probes = sources.associate { it.sourceId to mediaProbe.inspect(it.uri) }
        val prompts = PromptRepository(object : PromptSource {
            override fun read(name: String): String = "system"
        })
        val requestFactory = UnderstandingRequestFactory(prompts)
        val model = DeterministicBoundaryModel()
        val inputsBySource = sources.associate { source ->
            val inputs = mutableListOf<PreparedAnalysisInput>()
            try {
                RequestBoundaryAnalysisInputProcessor(
                    sourceBytesReader = AndroidSourceBytesReader(context.contentResolver),
                    sourceMetadataReader = AndroidSourceMetadataReader(context),
                    requestFactory = requestFactory,
                    mediaAdapter = AndroidAnalysisMediaAdapter(context),
                ).process(source, probes.getValue(source.sourceId), inputs::add)
            } catch (failure: PipelineException) {
                throw AssertionError("input preparation failed for ${source.sourceId}", failure)
            }
            source.sourceId to inputs
        }
        var savedDirectory: File? = null
        val privateStore = PrivateRunStore(context)
        val store = object : RunStore {
            override fun saveFinalJson(understandingJson: String, editPlanJson: String): File =
                privateStore.saveFinalJson(understandingJson, editPlanJson).also { savedDirectory = it }
        }

        try {
            val result = try {
                VlogPipeline(
                sourceBytesReader = SourceBytesReader { _, _ -> error("precomputed inputs own bytes") },
                sourceMetadataReader = SourceMetadataReader { error("precomputed inputs own metadata") },
                understandingRequestFactory = requestFactory,
                editingRequestFactory = EditingRequestFactory(prompts),
                modelClient = model,
                runStore = store,
                onPhase = {},
                analysisInputProcessor = AnalysisInputProcessor { source, _, consume ->
                    inputsBySource.getValue(source.sourceId).forEach { consume(it) }
                },
                ).run(sources, probes)
            } catch (failure: PipelineException) {
                throw AssertionError(
                    "pipeline failed after ${model.understandingCalls} understanding and ${model.editingCalls} editing calls",
                    failure,
                )
            }

            val runDirectory = requireNotNull(savedDirectory)
            Json.parseToJsonElement(runDirectory.resolve("video_understanding.json").readText())
            Json.parseToJsonElement(runDirectory.resolve("edit_plan.json").readText())
            assertEquals(2, result.understanding.sources.size)
            assertTrue(model.understandingCalls > 2)
            assertEquals(1, model.editingCalls)

            Media3Renderer(context).render(
                buildRenderSpec(
                    result.plan,
                    sources.associateBy(SelectedSource::sourceId),
                    probes,
                ),
                output,
            )
            val inspection = inspect(output)
            assertTrue(output.length() > 0L)
            assertEquals(1, inspection.trackMimeTypes.count { it.startsWith("video/") })
            assertTrue(inspection.videoSamples > 0)
            assertTrue(inspection.hasAudio)
        } finally {
            savedDirectory?.deleteRecursively()
            output.delete()
            assertTrue(secondFile.delete())
            assertTrue(sourceFile.delete())
        }
    }

    @Test
    fun proxy_isParseableH264AacAndDeletesItsTemporaryFileOnClose() = runBlocking {
        val uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO)
        val durationUs = requireNotNull(AndroidMediaProbe(context).inspect(uri).durationUs)
        val artifact = AndroidAnalysisMediaAdapter(context)
            .proxy(uri, 0L, durationUs, hasAudio = true) as FileAnalysisMediaArtifact
        val result = artifact.read(Int.MAX_VALUE) as SourceBytesReadResult.Fits
        val copy = File.createTempFile("analysis-proxy-copy-", ".mp4", context.cacheDir)
        copy.writeBytes(result.bytes)

        try {
            val inspection = inspect(copy)
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), inspection.trackMimeTypes)
            assertTrue(inspection.videoSamples > 0)
            assertTrue(artifact.file.isFile)
        } finally {
            artifact.close()
            assertFalse(artifact.file.exists())
            copy.delete()
        }
    }

    @Test
    fun hlgProxy_isParseableH264SdrAndDeletesItsTemporaryFileOnClose() = runBlocking {
        val uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_HEVC_HLG)
        val durationUs = requireNotNull(AndroidMediaProbe(context).inspect(uri).durationUs)
        val artifact = AndroidAnalysisMediaAdapter(context)
            .proxy(uri, 0L, durationUs, hasAudio = false) as FileAnalysisMediaArtifact
        val result = artifact.read(Int.MAX_VALUE) as SourceBytesReadResult.Fits
        val copy = File.createTempFile("analysis-hlg-proxy-copy-", ".mp4", context.cacheDir)
        copy.writeBytes(result.bytes)

        try {
            val inspection = inspect(copy)
            assertEquals(setOf("video/avc"), inspection.trackMimeTypes)
            assertTrue(inspection.videoSamples > 0)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, videoColorTransfer(copy))
            assertTrue(artifact.file.isFile)
        } finally {
            artifact.close()
            assertFalse(artifact.file.exists())
            copy.delete()
        }
    }

    private fun videoColorTransfer(file: File): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoFormat = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .single { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
            if (videoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                videoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            } else {
                null
            }
        } finally {
            extractor.release()
        }
    }

    private fun inspect(file: File): Inspection {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var videoSamples = 0
            val trackMimeTypes = mutableSetOf<String>()
            repeat(extractor.trackCount) { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                trackMimeTypes += mime
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    extractor.selectTrack(index)
                    while (extractor.sampleTime >= 0L) {
                        videoSamples += 1
                        extractor.advance()
                    }
                    extractor.unselectTrack(index)
                }
                if (mime.startsWith("audio/")) hasAudio = true
            }
            Inspection(hasVideo, hasAudio, videoSamples, trackMimeTypes)
        } finally {
            extractor.release()
        }
    }

    private fun repeatVp9WebmUntilApproximate31Mb(sourceUri: Uri, output: File) {
        val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(sourceUri, "r"))
        descriptor.use {
            val extractor = MediaExtractor()
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            var muxerStarted = false
            try {
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                val videoTrack = (0 until extractor.trackCount).single { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val format = extractor.getTrackFormat(videoTrack)
                val cycleDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                val outputTrack = muxer.addTrack(format)
                extractor.selectTrack(videoTrack)
                muxer.start()
                muxerStarted = true
                val buffer = ByteBuffer.allocateDirect(1_000_000)
                val bufferInfo = MediaCodec.BufferInfo()
                var writtenPayloadBytes = 0L
                var cycle = 0L
                while (writtenPayloadBytes < APPROXIMATE_31_MB_PAYLOAD_BYTES) {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    while (extractor.sampleTime >= 0L && writtenPayloadBytes < APPROXIMATE_31_MB_PAYLOAD_BYTES) {
                        buffer.clear()
                        val bytesRead = extractor.readSampleData(buffer, 0)
                        require(bytesRead > 0)
                        bufferInfo.set(
                            0,
                            bytesRead,
                            cycle * cycleDurationUs + extractor.sampleTime,
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                0
                            },
                        )
                        muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                        writtenPayloadBytes += bytesRead
                        if (!extractor.advance()) break
                    }
                    cycle += 1
                }
            } finally {
                extractor.release()
                try {
                    if (muxerStarted) muxer.stop()
                } finally {
                    muxer.release()
                }
            }
        }
    }

    private fun requestFactory() = UnderstandingRequestFactory(
        PromptRepository(object : PromptSource {
            override fun read(name: String): String = "system"
        }),
    )

    private data class Inspection(
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val videoSamples: Int,
        val trackMimeTypes: Set<String>,
    )

    private class CountingAnalysisMediaAdapter(
        private val delegate: AnalysisMediaAdapter,
    ) : AnalysisMediaAdapter {
        var remuxCalls = 0
        var proxyCalls = 0

        override suspend fun syncSampleStarts(uri: Uri): List<Long> = delegate.syncSampleStarts(uri)

        override suspend fun remux(uri: Uri, startUs: Long, endUs: Long): AnalysisMediaArtifact {
            remuxCalls += 1
            return delegate.remux(uri, startUs, endUs)
        }

        override suspend fun proxy(
            uri: Uri,
            startUs: Long,
            endUs: Long,
            hasAudio: Boolean,
        ): AnalysisMediaArtifact {
            proxyCalls += 1
            return delegate.proxy(uri, startUs, endUs, hasAudio)
        }
    }

    private class DeterministicBoundaryModel : OneShotModelClient {
        private val eventIds = mutableListOf<String>()
        var understandingCalls = 0
        var editingCalls = 0

        override suspend fun generate(request: ModelRequest): AiRawResult {
            val task = (request.parts.last() as ModelPart.Text).value
            if (request.parts.first() is ModelPart.InlineVideo) {
                understandingCalls += 1
                val sourceId = task.metadataValue("source_id")
                val segmentId = task.metadataValue("segment_id")
                val segmentDuration = task.metadataValue("segment_duration_s")
                val coreStart = task.metadataValue("reporting_core_start_in_segment_s")
                val coreEnd = task.metadataValue("reporting_core_end_in_segment_s")
                val eventId = "$segmentId-event"
                eventIds += eventId
                val continuesBefore = coreStart.toBigDecimal().signum() > 0
                val continuesAfter = coreEnd.toBigDecimal().compareTo(segmentDuration.toBigDecimal()) < 0
                return AiRawResult(
                    """{"source_id":"$sourceId","segment_id":"$segmentId","segment_duration_s":$segmentDuration,"events":[{"event_id":"$eventId","start_in_segment_s":$coreStart,"end_in_segment_s":$coreEnd,"continues_before":$continuesBefore,"continues_after":$continuesAfter,"description":"synthetic technical event","audio_description":null}]}""",
                    "fake-model",
                )
            }
            editingCalls += 1
            val selected = listOf(eventIds.last())
            val clips = selected.joinToString(",") { eventId ->
                """{"event_id":"$eventId","story_role":"technical","selection_reason":"synthetic"}"""
            }
            return AiRawResult("""{"clips":[$clips]}""", "fake-model")
        }

        private fun String.metadataValue(name: String): String =
            lineSequence().first { it.trimStart().startsWith("$name:") }.substringAfter(':').trim()
    }

    private companion object {
        const val APPROXIMATE_31_MB_PAYLOAD_BYTES = 31_000_000L
    }
}
