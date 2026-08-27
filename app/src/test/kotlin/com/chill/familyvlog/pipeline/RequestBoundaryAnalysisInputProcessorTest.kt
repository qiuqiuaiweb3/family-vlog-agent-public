package android.net

import android.os.Parcel
import com.chill.familyvlog.ai.PromptRepository
import com.chill.familyvlog.ai.PromptSource
import com.chill.familyvlog.ai.SourceBytesReadResult
import com.chill.familyvlog.ai.SourceBytesReader
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.analysis.SourceMetadataReadResult
import com.chill.familyvlog.analysis.SourceMetadataReader
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.VideoTrackInfo
import com.chill.familyvlog.pipeline.AnalysisMediaAdapter
import com.chill.familyvlog.pipeline.AnalysisMediaArtifact
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RequestBoundaryAnalysisInputProcessor
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.pipeline.fullSourceWindow
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequestBoundaryAnalysisInputProcessorTest {
    @Test
    fun `supported source inside duration and complete request budget is passed through unchanged`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val metadata = JsonObject(mapOf("title" to JsonPrimitive("original")))
        var metadataCalls = 0
        val adapter = RecordingAdapter()
        val processor = RequestBoundaryAnalysisInputProcessor(
            sourceBytesReader = SourceBytesReader { _, maxBytes ->
                assertTrue(maxBytes >= bytes.size)
                SourceBytesReadResult.Fits(bytes)
            },
            sourceMetadataReader = SourceMetadataReader {
                metadataCalls += 1
                SourceMetadataReadResult(metadata, emptyList())
            },
            requestFactory = requestFactory(),
            mediaAdapter = adapter,
        )
        val consumed = mutableListOf<com.chill.familyvlog.pipeline.PreparedAnalysisInput>()

        processor.process(source(), probe(durationUs = 2_000_000)) { consumed += it }

        assertEquals(1, metadataCalls)
        assertEquals(1, consumed.size)
        assertArrayEquals(bytes, consumed.single().bytes)
        assertSame(metadata, consumed.single().sourceMetadata)
        assertEquals("video/mp4", consumed.single().mimeType)
        assertEquals(0, adapter.scanCalls)
        assertTrue(adapter.remuxCalls.isEmpty())
        assertTrue(adapter.proxyCalls.isEmpty())
    }

    @Test
    fun `oversized source dynamically shrinks at irregular sync boundaries and covers the full timeline`() = runBlocking {
        val metadata = JsonObject(mapOf("title" to JsonPrimitive("same object")))
        val artifacts = mutableListOf<RecordingArtifact>()
        val adapter = RecordingAdapter(
            syncStarts = listOf(0L, 3_000_200L, 7_000_500L, 13_000_700L),
            remuxSize = { startUs, endUs ->
                when (startUs to endUs) {
                    0L to 20_000_900L -> 31_000_000
                    0L to 7_000_500L -> 10_000_000
                    0L to 13_000_700L -> 13_000_000
                    7_000_500L to 20_000_900L -> 22_000_000
                    7_000_500L to 13_000_700L -> 9_000_000
                    3_000_200L to 20_000_900L -> 25_000_000
                    3_000_200L to 13_000_700L -> 12_000_000
                    13_000_700L to 20_000_900L -> 12_000_000
                    else -> error("unexpected interval $startUs..$endUs")
                }
            },
            artifacts = artifacts,
        )
        var metadataCalls = 0
        val processor = RequestBoundaryAnalysisInputProcessor(
            sourceBytesReader = SourceBytesReader { _, _ -> SourceBytesReadResult.TooLarge },
            sourceMetadataReader = SourceMetadataReader {
                metadataCalls += 1
                SourceMetadataReadResult(metadata, emptyList())
            },
            requestFactory = requestFactory(),
            mediaAdapter = adapter,
        )
        val windows = mutableListOf<com.chill.familyvlog.contract.SourceWindow>()
        val metadataValues = mutableListOf<JsonObject>()
        val byteCounts = mutableListOf<Int>()

        processor.process(source(), probe(durationUs = 20_000_900)) { input ->
            windows += input.window
            metadataValues += input.sourceMetadata
            byteCounts += input.bytes.size
        }

        assertEquals(1, metadataCalls)
        assertEquals(listOf("source_s01", "source_s02", "source_s03"), windows.map { it.segmentId })
        assertEquals(
            listOf("0.000000" to "13.000700", "3.000200" to "13.000700", "13.000700" to "20.000900"),
            windows.map { it.segmentSourceStart.toPlainString() to it.segmentSourceEnd.toPlainString() },
        )
        assertEquals(
            listOf("0.000000" to "7.000500", "4.000300" to "10.000500", "0.000000" to "7.000200"),
            windows.map {
                it.reportingCoreStartInSegment.toPlainString() to it.reportingCoreEndInSegment.toPlainString()
            },
        )
        assertEquals(listOf(13_000_000, 12_000_000, 12_000_000), byteCounts)
        windows.zipWithNext().forEach { (first, second) ->
            val firstCoreEnd = first.segmentSourceStart.add(first.reportingCoreEndInSegment)
            val secondCoreStart = second.segmentSourceStart.add(second.reportingCoreStartInSegment)
            assertEquals(firstCoreEnd, secondCoreStart)
        }
        assertTrue(metadataValues.all { it === metadata })
        assertTrue(artifacts.all { it.closed })
        assertTrue(adapter.proxyCalls.isEmpty())
    }

    @Test
    fun `unsupported verified container uses exactly one H264 AAC proxy and cleans it after consume`() = runBlocking {
        val artifacts = mutableListOf<RecordingArtifact>()
        val adapter = RecordingAdapter(
            syncStarts = listOf(0L, 4_000_000L),
            proxySize = { _, _ -> 1_024 },
            artifacts = artifacts,
        )
        val processor = RequestBoundaryAnalysisInputProcessor(
            sourceBytesReader = SourceBytesReader { _, _ -> error("unsupported source must not be read directly") },
            sourceMetadataReader = SourceMetadataReader {
                SourceMetadataReadResult(JsonObject(emptyMap()), emptyList())
            },
            requestFactory = requestFactory(),
            mediaAdapter = adapter,
        )
        val consumedMimeTypes = mutableListOf<String>()

        processor.process(source(), probe(durationUs = 9_000_000, mime = "video/avi")) {
            consumedMimeTypes += it.mimeType
        }

        assertEquals(listOf(0L to 9_000_000L), adapter.proxyCalls)
        assertTrue(adapter.remuxCalls.isEmpty())
        assertEquals(listOf("video/mp4"), consumedMimeTypes)
        assertTrue(artifacts.single().closed)
    }

    @Test
    fun `complete prompt budget alone can change whole source pass through into segmentation`() = runBlocking {
        val factory = requestFactory()
        val selectedSource = source()
        val selectedProbe = probe(durationUs = 10_000_000)
        val window = fullSourceWindow(selectedSource, selectedProbe)
        val lowMetadata = JsonObject(emptyMap())
        val highMetadata = JsonObject(mapOf("xmp" to JsonPrimitive("\\\"".repeat(20_000))))
        val lowMax = factory.maxInlineVideoBytes(window, lowMetadata, "video/mp4")
        val highMax = factory.maxInlineVideoBytes(window, highMetadata, "video/mp4")
        val sourceByteCount = highMax + 1
        assertTrue(sourceByteCount <= lowMax)

        suspend fun run(metadata: JsonObject): RecordingAdapter {
            val adapter = RecordingAdapter(
                remuxSize = { _, _ -> 1_024 },
            )
            RequestBoundaryAnalysisInputProcessor(
                sourceBytesReader = SourceBytesReader { _, maxBytes ->
                    if (sourceByteCount <= maxBytes) {
                        SourceBytesReadResult.Fits(ByteArray(sourceByteCount))
                    } else {
                        SourceBytesReadResult.TooLarge
                    }
                },
                sourceMetadataReader = SourceMetadataReader {
                    SourceMetadataReadResult(metadata, emptyList())
                },
                requestFactory = factory,
                mediaAdapter = adapter,
            ).process(selectedSource, selectedProbe) {}
            return adapter
        }

        val lowPath = run(lowMetadata)
        val highPath = run(highMetadata)

        assertEquals(0, lowPath.scanCalls)
        assertTrue(lowPath.remuxCalls.isEmpty())
        assertEquals(1, highPath.scanCalls)
        assertEquals(listOf(0L to 10_000_000L, 0L to 10_000_000L), highPath.remuxCalls)
    }

    @Test
    fun `model failure and cancellation clean the current derived file without preparing later requests`() = runBlocking {
        listOf(
            PipelineException(RunPhase.ANALYZING, RunFailureCode.UNDERSTANDING_FAILED),
            CancellationException("cancel model"),
        ).forEach { expected ->
            val artifacts = mutableListOf<RecordingArtifact>()
            val adapter = RecordingAdapter(
                syncStarts = listOf(0L, 5_000_000L, 11_000_000L),
                remuxSize = { startUs, endUs ->
                    when (startUs to endUs) {
                        0L to 11_000_000L -> 30_000_000
                        0L to 5_000_000L -> 20_000_000
                        else -> error("later interval must not be prepared")
                    }
                },
                proxySize = { _, _ -> 1_024 },
                artifacts = artifacts,
            )
            val processor = RequestBoundaryAnalysisInputProcessor(
                sourceBytesReader = SourceBytesReader { _, _ -> SourceBytesReadResult.TooLarge },
                sourceMetadataReader = SourceMetadataReader {
                    SourceMetadataReadResult(JsonObject(emptyMap()), emptyList())
                },
                requestFactory = requestFactory(),
                mediaAdapter = adapter,
            )

            try {
                processor.process(source(), probe(durationUs = 11_000_000)) { throw expected }
                fail("expected $expected")
            } catch (actual: Exception) {
                assertSame(expected, actual)
            }

            assertEquals(listOf(0L to 11_000_000L, 0L to 5_000_000L), adapter.remuxCalls)
            assertEquals(listOf(0L to 11_000_000L), adapter.proxyCalls)
            assertTrue(artifacts.all { it.closed })
        }
    }

    @Test
    fun `small source over forty minutes is segmented instead of read whole or rejected`() = runBlocking {
        val twentyMinutes = 20L * 60L * 1_000_000L
        val fortyOneMinutes = 41L * 60L * 1_000_000L
        var sourceReadCalls = 0
        val adapter = RecordingAdapter(
            syncStarts = listOf(0L, twentyMinutes),
            remuxSize = { _, _ -> 1_024 },
        )
        val processor = RequestBoundaryAnalysisInputProcessor(
            sourceBytesReader = SourceBytesReader { _, _ ->
                sourceReadCalls += 1
                SourceBytesReadResult.Fits(byteArrayOf(1))
            },
            sourceMetadataReader = SourceMetadataReader {
                SourceMetadataReadResult(JsonObject(emptyMap()), emptyList())
            },
            requestFactory = requestFactory(),
            mediaAdapter = adapter,
        )
        val windows = mutableListOf<Pair<String, String>>()

        processor.process(source(), probe(durationUs = fortyOneMinutes)) {
            windows += it.window.segmentSourceStart.toPlainString() to it.window.segmentSourceEnd.toPlainString()
        }

        assertEquals(0, sourceReadCalls)
        assertEquals(
            listOf(
                0L to twentyMinutes,
                0L to twentyMinutes,
                twentyMinutes to fortyOneMinutes,
                twentyMinutes to fortyOneMinutes,
            ),
            adapter.remuxCalls,
        )
        assertEquals(
            listOf("0.000000" to "1200.000000", "1200.000000" to "2460.000000"),
            windows,
        )
    }

    @Test
    fun `WebM pass through remux request uses the verified artifact container MIME`() = runBlocking {
        val adapter = RecordingAdapter(
            remuxSize = { _, _ -> 1_024 },
            remuxMimeType = "video/webm",
        )
        val processor = RequestBoundaryAnalysisInputProcessor(
            sourceBytesReader = SourceBytesReader { _, _ -> SourceBytesReadResult.TooLarge },
            sourceMetadataReader = SourceMetadataReader {
                SourceMetadataReadResult(JsonObject(emptyMap()), emptyList())
            },
            requestFactory = requestFactory(),
            mediaAdapter = adapter,
        )
        val consumedMimes = mutableListOf<String>()

        processor.process(source(), probe(durationUs = 1_000_000, mime = "video/webm")) {
            consumedMimes += it.mimeType
        }

        assertEquals(listOf("video/webm"), consumedMimes)
    }

    private class RecordingAdapter(
        private val syncStarts: List<Long> = listOf(0L),
        private val remuxSize: ((Long, Long) -> Int)? = null,
        private val remuxMimeType: String = "video/mp4",
        private val proxySize: ((Long, Long) -> Int)? = null,
        private val artifacts: MutableList<RecordingArtifact> = mutableListOf(),
    ) : AnalysisMediaAdapter {
        var scanCalls = 0
        val remuxCalls = mutableListOf<Pair<Long, Long>>()
        val proxyCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun syncSampleStarts(uri: Uri): List<Long> {
            scanCalls += 1
            return syncStarts
        }

        override suspend fun remux(uri: Uri, startUs: Long, endUs: Long): AnalysisMediaArtifact {
            remuxCalls += startUs to endUs
            val artifact = RecordingArtifact(
                requireNotNull(remuxSize)(startUs, endUs),
                remuxMimeType,
            )
            artifacts += artifact
            return artifact
        }

        override suspend fun proxy(
            uri: Uri,
            startUs: Long,
            endUs: Long,
            hasAudio: Boolean,
        ): AnalysisMediaArtifact {
            proxyCalls += startUs to endUs
            val artifact = RecordingArtifact(
                requireNotNull(proxySize)(startUs, endUs),
                "video/mp4",
            )
            artifacts += artifact
            return artifact
        }
    }

    private class RecordingArtifact(
        private val byteCount: Int,
        override val mimeType: String,
    ) : AnalysisMediaArtifact {
        override val sizeBytes: Long
            get() = if (closed) 0L else byteCount.toLong()
        var closed = false

        override suspend fun read(maxBytes: Int): SourceBytesReadResult =
            if (byteCount <= maxBytes) {
                SourceBytesReadResult.Fits(ByteArray(byteCount))
            } else {
                SourceBytesReadResult.TooLarge
            }

        override fun close() {
            check(!closed)
            closed = true
        }
    }

    private companion object {
        fun requestFactory() = UnderstandingRequestFactory(
            PromptRepository(object : PromptSource {
                override fun read(name: String): String = "system"
            }),
        )

        fun source() = SelectedSource(1, "source", BoundaryUri)

        fun probe(durationUs: Long, mime: String? = "video/mp4") = ProbeResult(
            readable = true,
            durationUs = durationUs,
            resolverMimeType = mime,
            containerMimeType = mime,
            videoTrack = VideoTrackInfo("video/avc", 1920, 1080, 0, 30f, null, null, null),
            audioTracks = emptyList(),
            firstSyncFrameDecoded = true,
        )
    }
}

private data object BoundaryUri : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = null
    override fun getPath(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = null
    override fun getSchemeSpecificPart(): String? = null
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = true
    override fun toString(): String = "boundary-uri"
    override fun describeContents(): Int = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = Unit
}
