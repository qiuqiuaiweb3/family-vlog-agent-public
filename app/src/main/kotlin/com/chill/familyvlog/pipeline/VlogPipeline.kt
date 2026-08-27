package com.chill.familyvlog.pipeline

import com.chill.familyvlog.ai.EditingRequestFactory
import com.chill.familyvlog.ai.OneShotModelClient
import com.chill.familyvlog.ai.SourceBytesReader
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.analysis.SourceMetadataReader
import com.chill.familyvlog.contract.Diagnostic
import com.chill.familyvlog.contract.EditingResponse
import com.chill.familyvlog.contract.ModelJsonCodec
import com.chill.familyvlog.contract.SegmentUnderstanding
import com.chill.familyvlog.contract.SourceWindow
import com.chill.familyvlog.contract.ValidatedEditPlan
import com.chill.familyvlog.contract.ValidatedSegment
import com.chill.familyvlog.contract.ValidationResult
import com.chill.familyvlog.contract.VideoUnderstanding
import com.chill.familyvlog.contract.mergeSegments as mergeValidatedSegments
import com.chill.familyvlog.contract.validatePlan as validateEditPlan
import com.chill.familyvlog.contract.validateSegment as validateSegmentUnderstanding
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import java.util.concurrent.CancellationException

data class SegmentDiagnostics(
    val sourceId: String,
    val segmentId: String,
    val diagnostics: List<Diagnostic>,
)

data class SourceInputDiagnostics(
    val sourceId: String,
    val diagnostics: List<Diagnostic>,
)

data class PipelineDiagnostics(
    val segments: List<SegmentDiagnostics>,
    val editPlan: List<Diagnostic>,
    val inputPreparation: List<SourceInputDiagnostics> = emptyList(),
)

data class PipelineResult(
    val understanding: VideoUnderstanding,
    val plan: ValidatedEditPlan,
    val diagnostics: PipelineDiagnostics,
)

class VlogPipeline(
    sourceBytesReader: SourceBytesReader,
    sourceMetadataReader: SourceMetadataReader,
    private val understandingRequestFactory: UnderstandingRequestFactory,
    private val editingRequestFactory: EditingRequestFactory,
    private val modelClient: OneShotModelClient,
    private val runStore: RunStore,
    private val onPhase: (RunPhase) -> Unit,
    private val decodeSegment: (String) -> SegmentUnderstanding = ModelJsonCodec::decodeSegmentUnderstanding,
    private val validateSegment: (SegmentUnderstanding, SourceWindow) -> ValidationResult<ValidatedSegment> =
        ::validateSegmentUnderstanding,
    private val mergeSegments: (List<ValidatedSegment>) -> VideoUnderstanding = ::mergeValidatedSegments,
    private val encodeUnderstanding: (VideoUnderstanding) -> String = ModelJsonCodec::encodeVideoUnderstanding,
    private val decodePlan: (String) -> EditingResponse = ModelJsonCodec::decodeEditingResponse,
    private val validatePlan: (EditingResponse, VideoUnderstanding) -> ValidationResult<ValidatedEditPlan> = ::validateEditPlan,
    private val encodePlan: (ValidatedEditPlan) -> String = ModelJsonCodec::encodeEditPlan,
    private val analysisInputProcessor: AnalysisInputProcessor = BoundedWholeSourceInputProcessor(
        sourceBytesReader,
        sourceMetadataReader,
        understandingRequestFactory,
    ),
) {
    suspend fun run(
        sources: List<SelectedSource>,
        probes: Map<String, ProbeResult>,
    ): PipelineResult {
        inPhase(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED) {
            require(sources.isNotEmpty())
            sources.forEach { source ->
                require(probes.containsKey(source.sourceId))
            }
        }

        val segmentDiagnostics = mutableListOf<SegmentDiagnostics>()
        val inputDiagnostics = mutableListOf<SourceInputDiagnostics>()
        val segments = mutableListOf<ValidatedSegment>()
        var analysisStarted = false
        sources.forEach { source ->
            val probe = probes[source.sourceId] ?: throw PipelineException(
                RunPhase.PREPARING,
                RunFailureCode.INPUT_PREPARATION_FAILED,
            )
            try {
                analysisInputProcessor.process(source, probe) { item ->
                    if (item.diagnostics.isNotEmpty()) {
                        inputDiagnostics += SourceInputDiagnostics(item.window.sourceId, item.diagnostics)
                    }
                    if (!analysisStarted) {
                        onPhase(RunPhase.ANALYZING)
                        analysisStarted = true
                    }
                    inPhaseWithoutCallback(RunPhase.ANALYZING, RunFailureCode.UNDERSTANDING_FAILED) {
                        item.requireWithinRequestBudget(understandingRequestFactory)
                        val response = modelClient.generate(
                            understandingRequestFactory.create(
                                window = item.window,
                                sourceMetadata = item.sourceMetadata,
                                bytes = item.bytes,
                                inlineVideoMimeType = item.mimeType,
                            ),
                        )
                        when (val result = validateSegment(decodeSegment(response.text), item.window)) {
                            is ValidationResult.Invalid -> throw IllegalArgumentException()
                            is ValidationResult.Valid -> {
                                if (result.diagnostics.isNotEmpty()) {
                                    segmentDiagnostics += SegmentDiagnostics(
                                        sourceId = item.window.sourceId,
                                        segmentId = item.window.segmentId,
                                        diagnostics = result.diagnostics,
                                    )
                                }
                                segments += result.value
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: PipelineException) {
                throw failure
            } catch (failure: Exception) {
                throw PipelineException(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED)
            }
        }
        val analyzed = inPhaseWithoutCallback(RunPhase.ANALYZING, RunFailureCode.UNDERSTANDING_FAILED) {
            require(analysisStarted)
            val understanding = mergeSegments(segments)
            AnalyzedRun(understanding, encodeUnderstanding(understanding))
        }

        return inPhase(RunPhase.PLANNING, RunFailureCode.EDITING_FAILED) {
            require(analyzed.understanding.sources.any { it.events.isNotEmpty() })
            continuePlanning(
                analyzed.understanding,
                analyzed.finalUnderstandingJson,
                segmentDiagnostics,
                inputDiagnostics,
            )
        }
    }

    private suspend fun continuePlanning(
        understanding: VideoUnderstanding,
        finalUnderstandingJson: String,
        segmentDiagnostics: List<SegmentDiagnostics>,
        inputDiagnostics: List<SourceInputDiagnostics>,
    ): PipelineResult = try {
        val rawPlan = decodePlan(modelClient.generate(editingRequestFactory.create(finalUnderstandingJson)).text)
        val validated = when (val result = validatePlan(rawPlan, understanding)) {
            is ValidationResult.Invalid -> throw IllegalArgumentException()
            is ValidationResult.Valid -> result
        }
        val finalEditPlanJson = encodePlan(validated.value)
        save(finalUnderstandingJson, finalEditPlanJson)
        PipelineResult(
            understanding = understanding,
            plan = validated.value,
            diagnostics = PipelineDiagnostics(
                segments = segmentDiagnostics,
                editPlan = validated.diagnostics,
                inputPreparation = inputDiagnostics,
            ),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: PipelineException) {
        throw failure
    } catch (failure: Exception) {
        throw PipelineException(RunPhase.PLANNING, RunFailureCode.EDITING_FAILED)
    }

    private fun save(finalUnderstandingJson: String, finalEditPlanJson: String) {
        try {
            runStore.saveFinalJson(finalUnderstandingJson, finalEditPlanJson)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RunStoreException) {
            throw PipelineException(RunPhase.PLANNING, failure.code)
        } catch (failure: Exception) {
            throw PipelineException(RunPhase.PLANNING, RunFailureCode.PRIVATE_STORAGE_FAILED)
        }
    }

    private suspend fun <T> inPhase(
        phase: RunPhase,
        failureCode: RunFailureCode,
        block: suspend () -> T,
    ): T = try {
        onPhase(phase)
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: PipelineException) {
        throw failure
    } catch (failure: Exception) {
        throw PipelineException(phase, failureCode)
    }

    private suspend fun <T> inPhaseWithoutCallback(
        phase: RunPhase,
        failureCode: RunFailureCode,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: PipelineException) {
        throw failure
    } catch (failure: Exception) {
        throw PipelineException(phase, failureCode)
    }

    private data class AnalyzedRun(
        val understanding: VideoUnderstanding,
        val finalUnderstandingJson: String,
    )
}
