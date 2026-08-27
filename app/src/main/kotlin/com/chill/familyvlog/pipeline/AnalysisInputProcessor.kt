package com.chill.familyvlog.pipeline

import com.chill.familyvlog.ai.InlineRequestBudget
import com.chill.familyvlog.ai.SourceBytesReadResult
import com.chill.familyvlog.ai.SourceBytesReader
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.ai.normalizedFirebaseInlineVideoMime
import com.chill.familyvlog.ai.resolveFirebaseInlineVideoMime
import com.chill.familyvlog.analysis.SourceMetadataReadResult
import com.chill.familyvlog.analysis.SourceMetadataReader
import com.chill.familyvlog.contract.Diagnostic
import com.chill.familyvlog.contract.SourceWindow
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.SourceDecision
import com.chill.familyvlog.input.evaluate
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject

interface AnalysisMediaArtifact : AutoCloseable {
    val sizeBytes: Long
    val mimeType: String

    suspend fun read(maxBytes: Int): SourceBytesReadResult
}

interface AnalysisMediaAdapter {
    suspend fun syncSampleStarts(uri: android.net.Uri): List<Long>

    suspend fun remux(uri: android.net.Uri, startUs: Long, endUs: Long): AnalysisMediaArtifact

    suspend fun proxy(
        uri: android.net.Uri,
        startUs: Long,
        endUs: Long,
        hasAudio: Boolean,
    ): AnalysisMediaArtifact
}

data class PreparedAnalysisInput(
    val window: SourceWindow,
    val sourceMetadata: JsonObject,
    val diagnostics: List<Diagnostic>,
    val bytes: ByteArray,
    val mimeType: String,
)

fun interface AnalysisInputProcessor {
    suspend fun process(
        source: SelectedSource,
        probe: ProbeResult,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    )
}

internal class BoundedWholeSourceInputProcessor(
    private val sourceBytesReader: SourceBytesReader,
    private val sourceMetadataReader: SourceMetadataReader,
    private val requestFactory: UnderstandingRequestFactory,
) : AnalysisInputProcessor {
    override suspend fun process(
        source: SelectedSource,
        probe: ProbeResult,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    ) {
        val input = prepareInput {
            require(evaluate(probe) is SourceDecision.Accepted)
            val window = fullSourceWindow(source, probe)
            val metadataResult = sourceMetadataReader.read(source.uri)
            val mimeType = resolveFirebaseInlineVideoMime(probe)
            val maxBytes = requestFactory.maxInlineVideoBytes(window, metadataResult.metadata, mimeType)
            val bytes = when (val result = sourceBytesReader.read(source.uri, maxBytes)) {
                is SourceBytesReadResult.Fits -> result.bytes
                SourceBytesReadResult.TooLarge -> throw analysisInputTooLarge()
            }
            PreparedAnalysisInput(
                window = window,
                sourceMetadata = metadataResult.metadata,
                diagnostics = metadataResult.diagnostics,
                bytes = bytes,
                mimeType = mimeType,
            )
        }
        consume(input)
    }
}

internal class RequestBoundaryAnalysisInputProcessor(
    private val sourceBytesReader: SourceBytesReader,
    private val sourceMetadataReader: SourceMetadataReader,
    private val requestFactory: UnderstandingRequestFactory,
    private val mediaAdapter: AnalysisMediaAdapter,
) : AnalysisInputProcessor {
    override suspend fun process(
        source: SelectedSource,
        probe: ProbeResult,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    ) {
        prepareInput {
            require(evaluate(probe) is SourceDecision.Accepted)
            val window = fullSourceWindow(source, probe)
            val metadataResult = sourceMetadataReader.read(source.uri)
            val mimeType = normalizedFirebaseInlineVideoMime(probe)
            if (mimeType != null && requireNotNull(probe.durationUs) <= MAX_WHOLE_SOURCE_DURATION_US) {
                val maxBytes = requestFactory.maxInlineVideoBytes(window, metadataResult.metadata, mimeType)
                when (val result = sourceBytesReader.read(source.uri, maxBytes)) {
                    is SourceBytesReadResult.Fits -> {
                        consume(
                            PreparedAnalysisInput(
                                window = window,
                                sourceMetadata = metadataResult.metadata,
                                diagnostics = metadataResult.diagnostics,
                                bytes = result.bytes,
                                mimeType = mimeType,
                            ),
                        )
                        return@prepareInput
                    }

                    SourceBytesReadResult.TooLarge -> Unit
                }
            }
            prepareDerivedInputs(source, probe, metadataResult, mimeType, consume)
        }
    }

    private suspend fun prepareDerivedInputs(
        source: SelectedSource,
        probe: ProbeResult,
        metadataResult: SourceMetadataReadResult,
        sourceMimeType: String?,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    ) {
        val durationUs = requireNotNull(probe.durationUs)
        val syncStarts = mediaAdapter.syncSampleStarts(source.uri)
            .filter { it >= 0L && it < durationUs }
            .distinct()
            .sorted()
        require(syncStarts.isNotEmpty() && syncStarts.first() == 0L)

        val endpoints = (syncStarts.drop(1) + durationUs).distinct().sorted()
        var startUs = 0L
        var segmentNumber = 1
        while (startUs < durationUs) {
            val eligibleEnds = endpoints.filter {
                it > startUs && it - startUs <= MAX_WHOLE_SOURCE_DURATION_US
            }
            require(eligibleEnds.isNotEmpty())
            var endUs = eligibleEnds.last()
            if (sourceMimeType == null) {
                val emitted = emitCoreWithContext(
                    source,
                    probe,
                    durationUs,
                    segmentNumber,
                    startUs,
                    endUs,
                    syncStarts,
                    endpoints,
                    metadataResult,
                    sourceMimeType,
                    consume,
                )
                if (!emitted) {
                    throw analysisInputTooLarge()
                }
                startUs = endUs
                segmentNumber += 1
                continue
            }
            while (true) {
                val window = segmentWindow(
                    source,
                    durationUs,
                    segmentNumber,
                    startUs,
                    endUs,
                    startUs,
                    endUs,
                )
                var artifactBytes = 0L
                var maxBytes = 0
                mediaAdapter.remux(source.uri, startUs, endUs).use { artifact ->
                    artifactBytes = artifact.sizeBytes
                    maxBytes = requestFactory.maxInlineVideoBytes(
                        window,
                        metadataResult.metadata,
                        artifact.mimeType,
                    )
                }
                if (artifactBytes <= maxBytes.toLong()) {
                    check(
                        emitCoreWithContext(
                            source,
                            probe,
                            durationUs,
                            segmentNumber,
                            startUs,
                            endUs,
                            syncStarts,
                            endpoints,
                            metadataResult,
                            sourceMimeType,
                            consume,
                        ),
                    )
                    startUs = endUs
                    segmentNumber += 1
                    break
                }

                val minimumEndUs = eligibleEnds.first()
                if (endUs == minimumEndUs) {
                    val proxyEmitted = emitCoreWithContext(
                        source,
                        probe,
                        durationUs,
                        segmentNumber,
                        startUs,
                        endUs,
                        syncStarts,
                        endpoints,
                        metadataResult,
                        null,
                        consume,
                    )
                    if (!proxyEmitted) {
                        throw analysisInputTooLarge()
                    }
                    startUs = endUs
                    segmentNumber += 1
                    break
                }
                endUs = dynamicallyShorterEnd(
                    startUs = startUs,
                    currentEndUs = endUs,
                    eligibleEnds = eligibleEnds,
                    artifactBytes = artifactBytes,
                    maxBytes = maxBytes,
                )
            }
        }
    }

    private suspend fun emitCoreWithContext(
        source: SelectedSource,
        probe: ProbeResult,
        sourceDurationUs: Long,
        segmentNumber: Int,
        coreStartUs: Long,
        coreEndUs: Long,
        syncStarts: List<Long>,
        endpoints: List<Long>,
        metadataResult: SourceMetadataReadResult,
        sourceMimeType: String?,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    ): Boolean {
        val previousStart = syncStarts.lastOrNull { it < coreStartUs } ?: coreStartUs
        val nextEnd = endpoints.firstOrNull { it > coreEndUs } ?: coreEndUs
        val candidates = listOf(
            previousStart to nextEnd,
            previousStart to coreEndUs,
            coreStartUs to nextEnd,
            coreStartUs to coreEndUs,
        ).distinct().filter { (startUs, endUs) ->
            endUs - startUs <= MAX_WHOLE_SOURCE_DURATION_US
        }
        for ((segmentStartUs, segmentEndUs) in candidates) {
            val artifact = if (sourceMimeType == null) {
                mediaAdapter.proxy(
                    source.uri,
                    segmentStartUs,
                    segmentEndUs,
                    hasAudio = probe.audioTracks.isNotEmpty(),
                )
            } else {
                mediaAdapter.remux(source.uri, segmentStartUs, segmentEndUs)
            }
            val emitted = artifact.use {
                emitArtifact(
                    source,
                    sourceDurationUs,
                    segmentNumber,
                    segmentStartUs,
                    segmentEndUs,
                    coreStartUs,
                    coreEndUs,
                    metadataResult,
                    artifact,
                    consume,
                )
            }
            if (emitted) return true
        }
        return false
    }

    private suspend fun emitArtifact(
        source: SelectedSource,
        sourceDurationUs: Long,
        segmentNumber: Int,
        segmentStartUs: Long,
        segmentEndUs: Long,
        coreStartUs: Long,
        coreEndUs: Long,
        metadataResult: SourceMetadataReadResult,
        artifact: AnalysisMediaArtifact,
        consume: suspend (PreparedAnalysisInput) -> Unit,
    ): Boolean {
        val window = segmentWindow(
            source,
            sourceDurationUs,
            segmentNumber,
            segmentStartUs,
            segmentEndUs,
            coreStartUs,
            coreEndUs,
        )
        val maxBytes = requestFactory.maxInlineVideoBytes(window, metadataResult.metadata, artifact.mimeType)
        return when (val result = artifact.read(maxBytes)) {
            is SourceBytesReadResult.Fits -> {
                consume(
                    PreparedAnalysisInput(
                        window = window,
                        sourceMetadata = metadataResult.metadata,
                        diagnostics = metadataResult.diagnostics,
                        bytes = result.bytes,
                        mimeType = artifact.mimeType,
                    ),
                )
                true
            }

            SourceBytesReadResult.TooLarge -> false
        }
    }

    private fun dynamicallyShorterEnd(
        startUs: Long,
        currentEndUs: Long,
        eligibleEnds: List<Long>,
        artifactBytes: Long,
        maxBytes: Int,
    ): Long {
        val scaledDuration = ((currentEndUs - startUs).toDouble() * maxBytes / artifactBytes * SHRINK_HEADROOM)
            .toLong()
            .coerceAtLeast(1L)
        val targetEndUs = startUs + scaledDuration
        return eligibleEnds.lastOrNull { it < currentEndUs && it <= targetEndUs }
            ?: eligibleEnds.last { it < currentEndUs }
    }

    private companion object {
        const val MAX_WHOLE_SOURCE_DURATION_US = 40L * 60L * 1_000_000L
        const val SHRINK_HEADROOM = 0.95
    }
}

private fun segmentWindow(
    source: SelectedSource,
    sourceDurationUs: Long,
    segmentNumber: Int,
    segmentStartUs: Long,
    segmentEndUs: Long,
    coreStartUs: Long,
    coreEndUs: Long,
): SourceWindow {
    require(
        segmentStartUs >= 0L &&
            coreStartUs >= segmentStartUs &&
            coreEndUs > coreStartUs &&
            segmentEndUs >= coreEndUs &&
            segmentEndUs <= sourceDurationUs,
    )
    val sourceDuration = BigDecimal.valueOf(sourceDurationUs, 6)
    val start = BigDecimal.valueOf(segmentStartUs, 6)
    val end = BigDecimal.valueOf(segmentEndUs, 6)
    val coreStart = BigDecimal.valueOf(coreStartUs - segmentStartUs, 6)
    val coreEnd = BigDecimal.valueOf(coreEndUs - segmentStartUs, 6)
    return SourceWindow(
        sourceOrder = source.sourceOrder,
        sourceId = source.sourceId,
        sourceDuration = sourceDuration,
        segmentId = "${source.sourceId}_s${segmentNumber.toString().padStart(2, '0')}",
        segmentSourceStart = start,
        segmentSourceEnd = end,
        segmentDuration = end.subtract(start),
        reportingCoreStartInSegment = coreStart,
        reportingCoreEndInSegment = coreEnd,
    )
}

internal fun fullSourceWindow(source: SelectedSource, probe: ProbeResult): SourceWindow {
    val exactDuration = BigDecimal.valueOf(requireNotNull(probe.durationUs), 6)
    val modelDuration = exactDuration.setScale(3, RoundingMode.HALF_UP)
    require(modelDuration.signum() > 0)
    return SourceWindow(
        sourceOrder = source.sourceOrder,
        sourceId = source.sourceId,
        sourceDuration = exactDuration,
        segmentId = "${source.sourceId}_s01",
        segmentSourceStart = BigDecimal.ZERO,
        segmentSourceEnd = exactDuration,
        segmentDuration = modelDuration,
    )
}

internal suspend fun <T> prepareInput(block: suspend () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: PipelineException) {
    throw failure
} catch (failure: Exception) {
    throw PipelineException(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED)
}

internal fun analysisInputTooLarge(): PipelineException =
    PipelineException(RunPhase.PREPARING, RunFailureCode.ANALYSIS_INPUT_TOO_LARGE)

internal fun PreparedAnalysisInput.requireWithinRequestBudget(
    requestFactory: UnderstandingRequestFactory,
) {
    val totalBytes = requestFactory.estimateTotalBytes(
        window = window,
        sourceMetadata = sourceMetadata,
        bytes = bytes,
        inlineVideoMimeType = mimeType,
    )
    if (totalBytes >= InlineRequestBudget.REQUEST_LIMIT_BYTES) throw analysisInputTooLarge()
}
