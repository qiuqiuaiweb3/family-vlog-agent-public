package com.chill.familyvlog.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.RejectionReason
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.SourceDecision
import com.chill.familyvlog.input.assignSourceIds
import com.chill.familyvlog.input.evaluate
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.pipeline.RunState
import com.chill.familyvlog.render.buildRenderSpec
import com.chill.familyvlog.subtitle.SubtitleException
import com.chill.familyvlog.subtitle.SubtitleFailureCode
import com.chill.familyvlog.subtitle.SubtitleJobResult
import com.chill.familyvlog.subtitle.SubtitlePhase
import com.chill.familyvlog.subtitle.SubtitleRunState
import com.chill.familyvlog.subtitle.SubtitleDiagnosticOperation
import com.chill.familyvlog.subtitle.logSubtitleDiagnostic
import com.chill.familyvlog.subtitle.subtitleDiagnostic
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel internal constructor(
    private val runtime: VlogRuntime?,
    firebaseConfigured: Boolean,
    private val subtitleRuntime: SubtitleRuntime? = runtime as? SubtitleRuntime,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UiState(
            setupError = if (firebaseConfigured && runtime != null) {
                null
            } else {
                SetupError.FIREBASE_NOT_CONFIGURED
            },
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var selectedSources: List<SelectedSource> = emptyList()
    private var runJob: Job? = null
    private var activeRunId: Any? = null
    private var subtitleJob: Job? = null
    private var activeSubtitleRunId: Any? = null

    fun confirmDisclosure() {
        if (runJob != null || _uiState.value.runState != RunState.Idle) return
        _uiState.update { it.copy(disclosureConfirmed = true) }
    }

    fun acceptSelection(uris: List<Uri>) {
        val current = _uiState.value
        if (
            uris.isEmpty() ||
            !current.disclosureConfirmed ||
            current.runState != RunState.Idle ||
            runJob != null
        ) {
            return
        }
        selectedSources = assignSourceIds(uris)
        _uiState.update {
            it.copy(
                selectedSourceIds = selectedSources.map(SelectedSource::sourceId),
                inputRejection = null,
                finalUri = null,
            )
        }
    }

    fun createVlog() {
        val current = _uiState.value
        val currentRuntime = runtime
        if (
            currentRuntime == null ||
            current.setupError != null ||
            current.runState != RunState.Idle ||
            selectedSources.isEmpty() ||
            runJob != null
        ) {
            return
        }

        _uiState.update { it.copy(runCancelRequested = false) }
        val runId = Any()
        activeRunId = runId
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                runOnce(runId, currentRuntime, selectedSources)
            } finally {
                if (activeRunId === runId) {
                    activeRunId = null
                    runJob = null
                }
            }
        }
        runJob = job
        job.start()
    }

    fun cancel() {
        val runId = activeRunId ?: return
        val job = runJob ?: return
        while (true) {
            val current = _uiState.value
            if (activeRunId !== runId ||
                runJob !== job ||
                current.runState !is RunState.Active ||
                current.runCancelRequested
            ) {
                return
            }
            if (_uiState.compareAndSet(current, current.copy(runCancelRequested = true))) {
                job.cancel()
                return
            }
        }
    }

    fun createSubtitles() {
        val current = _uiState.value
        val currentRuntime = subtitleRuntime
        val source = current.finalUri
        if (
            currentRuntime == null ||
            current.runState != RunState.Succeeded ||
            source == null ||
            !current.canAddSubtitles ||
            subtitleJob != null
        ) {
            return
        }
        _uiState.update { it.copy(subtitleCancelRequested = false) }
        val runId = Any()
        activeSubtitleRunId = runId
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                runSubtitleJob(runId, currentRuntime, source)
            } finally {
                if (activeSubtitleRunId === runId) {
                    activeSubtitleRunId = null
                    subtitleJob = null
                }
            }
        }
        subtitleJob = job
        setSubtitleActive(runId, SubtitlePhase.TRANSCRIBING)
        job.start()
    }

    fun cancelSubtitles() {
        val runId = activeSubtitleRunId ?: return
        val job = subtitleJob ?: return
        while (true) {
            val current = _uiState.value
            if (activeSubtitleRunId !== runId ||
                subtitleJob !== job ||
                current.subtitleRunState !is SubtitleRunState.Active ||
                current.subtitleCancelRequested
            ) {
                return
            }
            if (_uiState.compareAndSet(current, current.copy(subtitleCancelRequested = true))) {
                job.cancel()
                return
            }
        }
    }

    private suspend fun runSubtitleJob(
        runId: Any,
        currentRuntime: SubtitleRuntime,
        source: Uri,
    ) {
        try {
            val result = currentRuntime.addSubtitles(
                source = source,
                onPhase = { phase -> setSubtitleActive(runId, phase) },
                onCommitted = { receipt -> setSubtitleSucceeded(runId, receipt.uri) },
            )
            when (result) {
                SubtitleJobResult.NoSpeech -> setSubtitleTerminal(runId) {
                    it.copy(subtitleRunState = SubtitleRunState.NoSpeech, subtitledUri = null)
                }
                is SubtitleJobResult.Published -> setSubtitleSucceeded(runId, result.receipt.uri)
            }
        } catch (failure: SubtitleException) {
            logSubtitleDiagnostic(
                subtitleDiagnostic(SubtitleDiagnosticOperation.SUBTITLE_JOB, failure),
            )
            setSubtitleFailed(runId, failure.phase, failure.code)
        } catch (_: CancellationException) {
            setSubtitleTerminal(runId) {
                it.copy(subtitleRunState = SubtitleRunState.Cancelled, subtitledUri = null)
            }
        } catch (_: LinkageError) {
            val phase = (_uiState.value.subtitleRunState as? SubtitleRunState.Active)?.phase
                ?: SubtitlePhase.TRANSCRIBING
            setSubtitleFailed(runId, phase, phase.defaultFailureCode())
        } catch (_: Exception) {
            val phase = (_uiState.value.subtitleRunState as? SubtitleRunState.Active)?.phase
                ?: SubtitlePhase.TRANSCRIBING
            setSubtitleFailed(runId, phase, phase.defaultFailureCode())
        }
    }

    private suspend fun runOnce(
        runId: Any,
        currentRuntime: VlogRuntime,
        sources: List<SelectedSource>,
    ) {
        try {
            setActive(runId, RunPhase.PREPARING)
            val probes = inspectSources(currentRuntime, sources)
            val result = currentRuntime.runPipeline(sources, probes) { phase -> setActive(runId, phase) }
            setActive(runId, RunPhase.RENDERING)
            val renderSpec = buildRenderSpec(
                plan = result.plan,
                sources = sources.associateBy(SelectedSource::sourceId),
                probes = probes,
            )
            val receipt = currentRuntime.export(
                spec = renderSpec,
                onSaving = { setActive(runId, RunPhase.SAVING) },
                onCommitted = { committed -> setSucceeded(runId, committed.uri) },
            )
            setSucceeded(runId, receipt.uri)
        } catch (rejection: InputRejectedException) {
            setFailed(
                runId = runId,
                phase = RunPhase.PREPARING,
                code = RunFailureCode.INPUT_PREPARATION_FAILED,
                inputRejection = rejection.reason,
            )
        } catch (failure: PipelineException) {
            setFailed(runId, failure.phase, failure.code)
        } catch (_: CancellationException) {
            setTerminal(runId) { it.copy(runState = RunState.Cancelled, finalUri = null) }
        } catch (_: Exception) {
            val phase = (_uiState.value.runState as? RunState.Active)?.phase ?: RunPhase.PREPARING
            setFailed(runId, phase, phase.defaultFailureCode())
        }
    }

    private suspend fun inspectSources(
        currentRuntime: VlogRuntime,
        sources: List<SelectedSource>,
    ): Map<String, ProbeResult> = buildMap {
        sources.forEach { source ->
            val probe = currentRuntime.inspect(source)
            when (val decision = evaluate(probe)) {
                SourceDecision.Accepted -> put(source.sourceId, probe)
                is SourceDecision.Rejected -> throw InputRejectedException(decision.reason)
            }
        }
    }

    private fun setActive(runId: Any, phase: RunPhase) {
        if (activeRunId !== runId) return
        _uiState.update { current ->
            if (current.runState.isTerminal()) {
                current
            } else {
                current.copy(
                    runState = RunState.Active(phase),
                    inputRejection = null,
                    finalUri = null,
                )
            }
        }
    }

    private fun setSucceeded(runId: Any, uri: Uri) {
        setTerminal(runId) { current ->
            current.copy(
                runState = RunState.Succeeded,
                inputRejection = null,
                finalUri = uri,
            )
        }
    }

    private fun setFailed(
        runId: Any,
        phase: RunPhase,
        code: RunFailureCode,
        inputRejection: RejectionReason? = null,
    ) {
        setTerminal(runId) { current ->
            current.copy(
                runState = RunState.Failed(phase, code),
                inputRejection = inputRejection,
                finalUri = null,
            )
        }
    }

    private fun setTerminal(runId: Any, updateState: (UiState) -> UiState) {
        if (activeRunId !== runId) return
        _uiState.update { current ->
            if (current.runState.isTerminal()) {
                current
            } else {
                updateState(current).copy(runCancelRequested = false)
            }
        }
    }

    private fun setSubtitleActive(runId: Any, phase: SubtitlePhase) {
        if (activeSubtitleRunId !== runId) return
        _uiState.update { current ->
            if (current.subtitleRunState == SubtitleRunState.Succeeded) current else current.copy(
                subtitleRunState = SubtitleRunState.Active(phase),
                subtitledUri = null,
            )
        }
    }

    private fun setSubtitleSucceeded(runId: Any, uri: Uri) {
        setSubtitleTerminal(runId) { current ->
            current.copy(
                subtitleRunState = SubtitleRunState.Succeeded,
                subtitledUri = uri,
            )
        }
    }

    private fun setSubtitleFailed(
        runId: Any,
        phase: SubtitlePhase,
        code: SubtitleFailureCode,
    ) {
        setSubtitleTerminal(runId) { current ->
            current.copy(
                subtitleRunState = SubtitleRunState.Failed(phase, code),
                subtitledUri = null,
            )
        }
    }

    private fun setSubtitleTerminal(runId: Any, updateState: (UiState) -> UiState) {
        if (activeSubtitleRunId !== runId) return
        _uiState.update { current ->
            if (current.subtitleRunState.isTerminal()) {
                current
            } else {
                updateState(current).copy(subtitleCancelRequested = false)
            }
        }
    }
}

private class InputRejectedException(val reason: RejectionReason) : RuntimeException(reason.name)

private fun RunPhase.defaultFailureCode(): RunFailureCode = when (this) {
    RunPhase.PREPARING -> RunFailureCode.INPUT_PREPARATION_FAILED
    RunPhase.ANALYZING -> RunFailureCode.UNDERSTANDING_FAILED
    RunPhase.PLANNING -> RunFailureCode.EDITING_FAILED
    RunPhase.RENDERING -> RunFailureCode.RENDER_FAILED
    RunPhase.SAVING -> RunFailureCode.PUBLISH_FAILED
}

private fun SubtitlePhase.defaultFailureCode(): SubtitleFailureCode = when (this) {
    SubtitlePhase.TRANSCRIBING -> SubtitleFailureCode.TRANSCRIPTION_FAILED
    SubtitlePhase.DOWNLOADING_MODEL -> SubtitleFailureCode.MODEL_DOWNLOAD_FAILED
    SubtitlePhase.TRANSLATING -> SubtitleFailureCode.TRANSLATION_FAILED
    SubtitlePhase.RENDERING -> SubtitleFailureCode.RENDER_FAILED
    SubtitlePhase.SAVING -> SubtitleFailureCode.PUBLISH_FAILED
}

private fun SubtitleRunState.isTerminal(): Boolean = when (this) {
    SubtitleRunState.Idle, is SubtitleRunState.Active -> false
    SubtitleRunState.NoSpeech,
    SubtitleRunState.Succeeded,
    is SubtitleRunState.Failed,
    SubtitleRunState.Cancelled,
    -> true
}
