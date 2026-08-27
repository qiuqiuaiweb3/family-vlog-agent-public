package com.chill.familyvlog.ui

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.chill.familyvlog.ai.AiRawResult
import com.chill.familyvlog.ai.EditingRequestFactory
import com.chill.familyvlog.ai.ModelPart
import com.chill.familyvlog.ai.ModelRequest
import com.chill.familyvlog.ai.OneShotModelClient
import com.chill.familyvlog.ai.PromptRepository
import com.chill.familyvlog.ai.PromptSource
import com.chill.familyvlog.ai.SourceBytesReader
import com.chill.familyvlog.ai.SourceBytesReadResult
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.analysis.SourceMetadataReadResult
import com.chill.familyvlog.analysis.SourceMetadataReader
import com.chill.familyvlog.ai.buildEditingTask
import com.chill.familyvlog.contract.Diagnostic
import com.chill.familyvlog.contract.EditClip
import com.chill.familyvlog.contract.SourceWindow
import com.chill.familyvlog.contract.UnderstandingEvent
import com.chill.familyvlog.contract.ValidatedEditClip
import com.chill.familyvlog.contract.ValidatedEditPlan
import com.chill.familyvlog.contract.ValidatedEvent
import com.chill.familyvlog.contract.ValidatedSegment
import com.chill.familyvlog.contract.VideoUnderstanding
import com.chill.familyvlog.contract.ValidationResult
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.RejectionReason
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.VideoTrackInfo
import com.chill.familyvlog.output.PublicationReceipt
import com.chill.familyvlog.pipeline.PipelineDiagnostics
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.PipelineResult
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.pipeline.RunStore
import com.chill.familyvlog.pipeline.RunState
import com.chill.familyvlog.pipeline.VlogPipeline
import com.chill.familyvlog.render.RenderSpec
import com.chill.familyvlog.subtitle.SubtitleFailureCode
import com.chill.familyvlog.subtitle.SubtitleJobResult
import com.chill.familyvlog.subtitle.SubtitlePhase
import com.chill.familyvlog.subtitle.SubtitleRunState
import java.math.BigDecimal
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import com.chill.familyvlog.contract.validateSegment as validateSegmentContract

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `disclosure gates ordered selection while missing Firebase stays idle`() = runTest(dispatcher) {
        val runtime = RecordingRuntime()
        val viewModel = MainViewModel(runtime, firebaseConfigured = false)
        val first = mock(Uri::class.java)
        val second = mock(Uri::class.java)

        viewModel.acceptSelection(listOf(first))
        assertTrue(viewModel.uiState.value.selectedSourceIds.isEmpty())

        viewModel.confirmDisclosure()
        viewModel.acceptSelection(listOf(second, first))
        viewModel.createVlog()
        runCurrent()

        assertEquals(listOf("video_01", "video_02"), viewModel.uiState.value.selectedSourceIds)
        assertEquals(SetupError.FIREBASE_NOT_CONFIGURED, viewModel.uiState.value.setupError)
        assertSame(RunState.Idle, viewModel.uiState.value.runState)
        assertEquals(0, runtime.pipelineCalls)
        assertEquals(0, runtime.exportCalls)
    }

    @Test
    fun `selection has no ten item runtime gate and terminal state cannot be changed`() = runTest(dispatcher) {
        val runtime = RecordingRuntime()
        val viewModel = MainViewModel(runtime, firebaseConfigured = true)
        val uris = List(11) { mock(Uri::class.java) }

        viewModel.confirmDisclosure()
        viewModel.acceptSelection(uris)
        viewModel.createVlog()
        runCurrent()

        assertEquals(11, runtime.inspected.size)
        assertEquals((1..11).map { "video_${it.toString().padStart(2, '0')}" }, viewModel.uiState.value.selectedSourceIds)
        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(runtime.receipt.uri, viewModel.uiState.value.finalUri)

        viewModel.acceptSelection(listOf(mock(Uri::class.java)))
        viewModel.createVlog()
        viewModel.cancel()
        runCurrent()

        assertEquals(11, viewModel.uiState.value.selectedSourceIds.size)
        assertEquals(1, runtime.pipelineCalls)
        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(runtime.receipt.uri, viewModel.uiState.value.finalUri)
    }

    @Test
    fun `one run completes the original flow without starting subtitle work`() = runTest(dispatcher) {
        val runtime = RecordingRuntime()
        val viewModel = readyViewModel(runtime)
        val states = mutableListOf<RunState>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiState.collect { state ->
                if (states.lastOrNull() != state.runState) states += state.runState
            }
        }

        viewModel.createVlog()
        runCurrent()

        assertEquals(
            listOf(
                RunState.Idle,
                RunState.Active(RunPhase.PREPARING),
                RunState.Active(RunPhase.ANALYZING),
                RunState.Active(RunPhase.PLANNING),
                RunState.Active(RunPhase.RENDERING),
                RunState.Active(RunPhase.SAVING),
                RunState.Succeeded,
            ),
            states,
        )
        assertEquals(1, runtime.pipelineCalls)
        assertEquals(1, runtime.exportCalls)
        assertEquals(0, runtime.subtitleCalls)
        assertSame(runtime.receipt.uri, viewModel.uiState.value.finalUri)
        assertSame(SubtitleRunState.Idle, viewModel.uiState.value.subtitleRunState)
        collector.cancel()
    }

    @Test
    fun `subtitle action is unavailable before success then preserves original and stores a separate result`() = runTest(dispatcher) {
        val runtime = RecordingRuntime()
        val viewModel = readyViewModel(runtime)

        viewModel.createSubtitles()
        runCurrent()
        assertEquals(0, runtime.subtitleCalls)

        viewModel.createVlog()
        runCurrent()
        val original = viewModel.uiState.value.finalUri
        viewModel.createSubtitles()
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(original, viewModel.uiState.value.finalUri)
        assertSame(SubtitleRunState.Succeeded, viewModel.uiState.value.subtitleRunState)
        assertSame(runtime.subtitleReceipt.uri, viewModel.uiState.value.subtitledUri)
        assertNotSame(original, viewModel.uiState.value.subtitledUri)
        assertEquals(listOf(runtime.receipt.uri), runtime.subtitleSources)
    }

    @Test
    fun `no speech ends only the subtitle job and does not create a duplicate`() = runTest(dispatcher) {
        val runtime = RecordingRuntime(
            subtitleAction = { _, _, _ -> SubtitleJobResult.NoSpeech },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        val original = viewModel.uiState.value.finalUri
        viewModel.createSubtitles()
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(original, viewModel.uiState.value.finalUri)
        assertSame(SubtitleRunState.NoSpeech, viewModel.uiState.value.subtitleRunState)
        assertNull(viewModel.uiState.value.subtitledUri)
        assertEquals(1, runtime.subtitleCalls)
    }

    @Test
    fun `retry replaces a prior terminal subtitle state even when runtime fails before phase callback`() =
        runTest(dispatcher) {
            var attempt = 0
            val runtime = RecordingRuntime(
                subtitleAction = { _, _, _ ->
                    attempt += 1
                    if (attempt == 1) {
                        SubtitleJobResult.NoSpeech
                    } else {
                        throw com.chill.familyvlog.subtitle.SubtitleException(
                            SubtitlePhase.TRANSLATING,
                            SubtitleFailureCode.TRANSLATION_FAILED,
                        )
                    }
                },
            )
            val viewModel = readyViewModel(runtime)

            viewModel.createVlog()
            runCurrent()
            val original = viewModel.uiState.value.finalUri
            viewModel.createSubtitles()
            runCurrent()
            assertSame(SubtitleRunState.NoSpeech, viewModel.uiState.value.subtitleRunState)

            viewModel.createSubtitles()
            runCurrent()

            assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
            assertSame(original, viewModel.uiState.value.finalUri)
            assertEquals(
                SubtitleRunState.Failed(
                    SubtitlePhase.TRANSLATING,
                    SubtitleFailureCode.TRANSLATION_FAILED,
                ),
                viewModel.uiState.value.subtitleRunState,
            )
            assertEquals(2, runtime.subtitleCalls)
        }

    @Test
    fun `subtitle failure is isolated from the completed original`() = runTest(dispatcher) {
        val runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, _ ->
                onPhase(SubtitlePhase.TRANSLATING)
                throw com.chill.familyvlog.subtitle.SubtitleException(
                    SubtitlePhase.TRANSLATING,
                    SubtitleFailureCode.TRANSLATION_FAILED,
                )
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        val original = viewModel.uiState.value.finalUri
        viewModel.createSubtitles()
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(original, viewModel.uiState.value.finalUri)
        assertEquals(
            SubtitleRunState.Failed(
                SubtitlePhase.TRANSLATING,
                SubtitleFailureCode.TRANSLATION_FAILED,
            ),
            viewModel.uiState.value.subtitleRunState,
        )
        assertNull(viewModel.uiState.value.subtitledUri)
    }

    @Test
    fun `cancelling subtitle work keeps the completed original`() = runTest(dispatcher) {
        val subtitleStarted = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, _ ->
                onPhase(SubtitlePhase.TRANSCRIBING)
                subtitleStarted.complete(Unit)
                awaitCancellation()
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        val original = viewModel.uiState.value.finalUri
        viewModel.createSubtitles()
        subtitleStarted.await()
        viewModel.cancelSubtitles()
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(original, viewModel.uiState.value.finalUri)
        assertSame(SubtitleRunState.Cancelled, viewModel.uiState.value.subtitleRunState)
        assertNull(viewModel.uiState.value.subtitledUri)
        assertEquals(1, runtime.subtitleCalls)
    }

    @Test
    fun `repeated subtitle action while active starts only one job`() = runTest(dispatcher) {
        val subtitleStarted = CompletableDeferred<Unit>()
        val subtitleRelease = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, _ ->
                onPhase(SubtitlePhase.TRANSCRIBING)
                subtitleStarted.complete(Unit)
                subtitleRelease.await()
                SubtitleJobResult.NoSpeech
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.createSubtitles()
        subtitleStarted.await()
        viewModel.createSubtitles()
        runCurrent()

        assertEquals(1, runtime.subtitleCalls)
        subtitleRelease.complete(Unit)
        runCurrent()
        assertSame(SubtitleRunState.NoSpeech, viewModel.uiState.value.subtitleRunState)
    }

    @Test
    fun `subtitle commit wins concurrent cancellation and keeps original uri`() = runTest(dispatcher) {
        val commitEntered = CompletableDeferred<Unit>()
        val commitRelease = CompletableDeferred<Unit>()
        lateinit var runtime: RecordingRuntime
        runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, onCommitted ->
                val callerJob = currentCoroutineContext()[Job]!!
                onPhase(SubtitlePhase.SAVING)
                withContext(NonCancellable) {
                    commitEntered.complete(Unit)
                    commitRelease.await()
                    onCommitted(runtime.subtitleReceipt)
                }
                callerJob.ensureActive()
                SubtitleJobResult.Published(runtime.subtitleReceipt)
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        val original = viewModel.uiState.value.finalUri
        viewModel.createSubtitles()
        commitEntered.await()
        viewModel.cancelSubtitles()
        commitRelease.complete(Unit)
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(original, viewModel.uiState.value.finalUri)
        assertSame(SubtitleRunState.Succeeded, viewModel.uiState.value.subtitleRunState)
        assertSame(runtime.subtitleReceipt.uri, viewModel.uiState.value.subtitledUri)
        assertFalse(viewModel.uiState.value.subtitleCancelRequested)
    }

    @Test
    fun originalCancelRequestIsSetBeforeCleanupAndSurvivesLatePhaseCallback() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        lateinit var latePhase: (RunPhase) -> Unit
        val runtime = RecordingRuntime(
            pipelineAction = { _, _, onPhase ->
                latePhase = onPhase
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.cancel()
        cleanupStarted.await()

        assertTrue(viewModel.uiState.value.runCancelRequested)
        latePhase(RunPhase.ANALYZING)
        assertEquals(RunState.Active(RunPhase.ANALYZING), viewModel.uiState.value.runState)
        assertTrue(viewModel.uiState.value.runCancelRequested)

        cleanupRelease.complete(Unit)
        runCurrent()
        assertSame(RunState.Cancelled, viewModel.uiState.value.runState)
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun repeatedOriginalCancelKeepsOneRunAndOneCleanup() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var cleanupEntries = 0
        val runtime = RecordingRuntime(
            pipelineAction = { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntries += 1
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.cancel()
        cleanupStarted.await()
        viewModel.cancel()
        runCurrent()

        assertEquals(1, runtime.pipelineCalls)
        assertEquals(1, cleanupEntries)
        assertTrue(viewModel.uiState.value.runCancelRequested)

        cleanupRelease.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun originalCancellationClearsCancelRequestAfterCleanup() = runTest(dispatcher) {
        val runtime = RecordingRuntime(
            pipelineAction = { _, _, _ -> awaitCancellation() },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.cancel()
        runCurrent()

        assertSame(RunState.Cancelled, viewModel.uiState.value.runState)
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun subtitleCancelRequestIsSetBeforeCleanupAndSurvivesLatePhaseCallback() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        lateinit var latePhase: (SubtitlePhase) -> Unit
        val runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, _ ->
                latePhase = onPhase
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            },
        )
        val viewModel = readyViewModel(runtime)
        viewModel.createVlog()
        runCurrent()

        viewModel.createSubtitles()
        runCurrent()
        viewModel.cancelSubtitles()
        cleanupStarted.await()

        assertTrue(viewModel.uiState.value.subtitleCancelRequested)
        latePhase(SubtitlePhase.TRANSLATING)
        assertEquals(
            SubtitleRunState.Active(SubtitlePhase.TRANSLATING),
            viewModel.uiState.value.subtitleRunState,
        )
        assertTrue(viewModel.uiState.value.subtitleCancelRequested)

        cleanupRelease.complete(Unit)
        runCurrent()
        assertSame(SubtitleRunState.Cancelled, viewModel.uiState.value.subtitleRunState)
        assertFalse(viewModel.uiState.value.subtitleCancelRequested)
    }

    @Test
    fun repeatedSubtitleCancelKeepsOneJobAndOneCleanup() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var cleanupEntries = 0
        val runtime = RecordingRuntime(
            subtitleAction = { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntries += 1
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            },
        )
        val viewModel = readyViewModel(runtime)
        viewModel.createVlog()
        runCurrent()

        viewModel.createSubtitles()
        runCurrent()
        viewModel.cancelSubtitles()
        cleanupStarted.await()
        viewModel.cancelSubtitles()
        runCurrent()

        assertEquals(1, runtime.subtitleCalls)
        assertEquals(1, cleanupEntries)
        assertTrue(viewModel.uiState.value.subtitleCancelRequested)

        cleanupRelease.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.subtitleCancelRequested)
    }

    @Test
    fun subtitleNoSpeechSuccessFailureAndCancellationAllClearCancelRequest() = runTest(dispatcher) {
        val noSpeech = RecordingRuntime(
            subtitleAction = { _, _, _ -> SubtitleJobResult.NoSpeech },
        )
        val noSpeechViewModel = readyViewModel(noSpeech)
        noSpeechViewModel.createVlog()
        runCurrent()
        noSpeechViewModel.createSubtitles()
        runCurrent()
        assertSame(SubtitleRunState.NoSpeech, noSpeechViewModel.uiState.value.subtitleRunState)
        assertFalse(noSpeechViewModel.uiState.value.subtitleCancelRequested)

        val succeeded = RecordingRuntime()
        val succeededViewModel = readyViewModel(succeeded)
        succeededViewModel.createVlog()
        runCurrent()
        succeededViewModel.createSubtitles()
        runCurrent()
        assertSame(SubtitleRunState.Succeeded, succeededViewModel.uiState.value.subtitleRunState)
        assertFalse(succeededViewModel.uiState.value.subtitleCancelRequested)

        val failed = RecordingRuntime(
            subtitleAction = { _, _, _ ->
                throw com.chill.familyvlog.subtitle.SubtitleException(
                    SubtitlePhase.TRANSLATING,
                    SubtitleFailureCode.TRANSLATION_FAILED,
                )
            },
        )
        val failedViewModel = readyViewModel(failed)
        failedViewModel.createVlog()
        runCurrent()
        failedViewModel.createSubtitles()
        runCurrent()
        assertTrue(failedViewModel.uiState.value.subtitleRunState is SubtitleRunState.Failed)
        assertFalse(failedViewModel.uiState.value.subtitleCancelRequested)

        val cancelled = RecordingRuntime(
            subtitleAction = { _, _, _ -> awaitCancellation() },
        )
        val cancelledViewModel = readyViewModel(cancelled)
        cancelledViewModel.createVlog()
        runCurrent()
        cancelledViewModel.createSubtitles()
        runCurrent()
        cancelledViewModel.cancelSubtitles()
        runCurrent()
        assertSame(SubtitleRunState.Cancelled, cancelledViewModel.uiState.value.subtitleRunState)
        assertFalse(cancelledViewModel.uiState.value.subtitleCancelRequested)
    }

    @Test
    fun subtitleRetryClearsCancelRequestBeforeTranscribing() = runTest(dispatcher) {
        val retryableStates = listOf<SubtitleRunState>(
            SubtitleRunState.NoSpeech,
            SubtitleRunState.Failed(SubtitlePhase.TRANSLATING, SubtitleFailureCode.TRANSLATION_FAILED),
            SubtitleRunState.Cancelled,
        )

        for (expectedTerminal in retryableStates) {
            var attempt = 0
            val secondStarted = CompletableDeferred<Unit>()
            val runtime = RecordingRuntime(
                subtitleAction = { _, _, _ ->
                    attempt += 1
                    if (attempt > 1) {
                        secondStarted.complete(Unit)
                        awaitCancellation()
                    }
                    when (expectedTerminal) {
                        SubtitleRunState.NoSpeech -> SubtitleJobResult.NoSpeech
                        is SubtitleRunState.Failed -> {
                            throw com.chill.familyvlog.subtitle.SubtitleException(
                                expectedTerminal.phase,
                                expectedTerminal.code,
                            )
                        }
                        SubtitleRunState.Cancelled -> awaitCancellation()
                        else -> error("Unexpected retry fixture: $expectedTerminal")
                    }
                },
            )
            val viewModel = readyViewModel(runtime)
            viewModel.createVlog()
            runCurrent()
            viewModel.createSubtitles()
            runCurrent()
            if (expectedTerminal == SubtitleRunState.Cancelled) {
                viewModel.cancelSubtitles()
                runCurrent()
            }
            assertEquals(expectedTerminal, viewModel.uiState.value.subtitleRunState)

            viewModel.createSubtitles()
            secondStarted.await()

            assertEquals(
                SubtitleRunState.Active(SubtitlePhase.TRANSCRIBING),
                viewModel.uiState.value.subtitleRunState,
            )
            assertFalse(viewModel.uiState.value.subtitleCancelRequested)
            viewModel.cancelSubtitles()
            runCurrent()
        }
    }

    @Test
    fun lateSubtitleCallbackFromPreviousRunCannotChangeCurrentRun() = runTest(dispatcher) {
        var attempt = 0
        lateinit var firstPhase: (SubtitlePhase) -> Unit
        val secondStarted = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime(
            subtitleAction = { _, onPhase, _ ->
                attempt += 1
                if (attempt == 1) {
                    firstPhase = onPhase
                    SubtitleJobResult.NoSpeech
                } else {
                    secondStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val viewModel = readyViewModel(runtime)
        viewModel.createVlog()
        runCurrent()
        viewModel.createSubtitles()
        runCurrent()
        viewModel.createSubtitles()
        secondStarted.await()

        firstPhase(SubtitlePhase.SAVING)

        assertEquals(
            SubtitleRunState.Active(SubtitlePhase.TRANSCRIBING),
            viewModel.uiState.value.subtitleRunState,
        )
        viewModel.cancelSubtitles()
        runCurrent()
    }

    @Test
    fun cancelFieldsAndResultUrisRemainFlowIsolated() = runTest(dispatcher) {
        val originalRuntime = RecordingRuntime(
            pipelineAction = { _, _, _ -> awaitCancellation() },
        )
        val originalViewModel = readyViewModel(originalRuntime)
        originalViewModel.createVlog()
        runCurrent()
        originalViewModel.cancel()
        runCurrent()

        assertFalse(originalViewModel.uiState.value.subtitleCancelRequested)
        assertSame(SubtitleRunState.Idle, originalViewModel.uiState.value.subtitleRunState)
        assertNull(originalViewModel.uiState.value.subtitledUri)

        val subtitleRuntime = RecordingRuntime(
            subtitleAction = { _, _, _ -> awaitCancellation() },
        )
        val subtitleViewModel = readyViewModel(subtitleRuntime)
        subtitleViewModel.createVlog()
        runCurrent()
        val originalUri = subtitleViewModel.uiState.value.finalUri
        subtitleViewModel.createSubtitles()
        runCurrent()
        subtitleViewModel.cancelSubtitles()
        runCurrent()

        assertFalse(subtitleViewModel.uiState.value.runCancelRequested)
        assertSame(RunState.Succeeded, subtitleViewModel.uiState.value.runState)
        assertSame(originalUri, subtitleViewModel.uiState.value.finalUri)
    }

    @Test
    fun `known hdr first followed by sdr fails before export starts`() = runTest(dispatcher) {
        val hdrProbe = validProbe.copy(
            videoTrack = validProbe.videoTrack!!.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_HLG),
        )
        val sdrProbe = validProbe.copy(
            videoTrack = validProbe.videoTrack!!.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
        )
        val runtime = RecordingRuntime(
            probesBySource = mapOf("video_01" to hdrProbe, "video_02" to sdrProbe),
            pipelineAction = { sources, _, _ ->
                val clips = sources.mapIndexed { index, source ->
                    val editClip = EditClip(
                        sourceId = source.sourceId,
                        eventId = "event_${index + 1}",
                        start = BigDecimal.ZERO,
                        end = BigDecimal.ONE,
                        storyRole = "development",
                        selectionReason = "fixture",
                    )
                    ValidatedEditClip(editClip, BigDecimal.ONE)
                }
                pipelineResult(sources.first().sourceId).copy(
                    plan = ValidatedEditPlan(clips, BigDecimal("2")),
                )
            },
        )
        val viewModel = MainViewModel(runtime, firebaseConfigured = true)
        viewModel.confirmDisclosure()
        viewModel.acceptSelection(listOf(mock(Uri::class.java), mock(Uri::class.java)))

        viewModel.createVlog()
        runCurrent()

        assertEquals(
            RunState.Failed(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED),
            viewModel.uiState.value.runState,
        )
        assertEquals(0, runtime.exportCalls)
    }

    @Test
    fun `cancel requests wait for cleanup and keep the single run occupied`() = runTest(dispatcher) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime(
            pipelineAction = { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.cancel()
        cleanupStarted.await()
        viewModel.createVlog()
        viewModel.acceptSelection(listOf(mock(Uri::class.java)))
        runCurrent()

        assertEquals(1, runtime.pipelineCalls)
        assertEquals(listOf("video_01"), viewModel.uiState.value.selectedSourceIds)
        assertTrue(viewModel.uiState.value.runState is RunState.Active)

        cleanupRelease.complete(Unit)
        runCurrent()

        assertSame(RunState.Cancelled, viewModel.uiState.value.runState)
        assertNull(viewModel.uiState.value.finalUri)
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun `cleanup failure overrides cancellation and becomes the only terminal failure`() = runTest(dispatcher) {
        val runtime = RecordingRuntime(
            pipelineAction = { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    throw PipelineException(RunPhase.RENDERING, RunFailureCode.PRIVATE_STORAGE_FAILED)
                }
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        viewModel.cancel()
        runCurrent()

        assertEquals(
            RunState.Failed(RunPhase.RENDERING, RunFailureCode.PRIVATE_STORAGE_FAILED),
            viewModel.uiState.value.runState,
        )
        assertNull(viewModel.uiState.value.finalUri)
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun `committed callback wins over concurrent cancellation and ignores late transitions`() = runTest(dispatcher) {
        val commitEntered = CompletableDeferred<Unit>()
        val commitRelease = CompletableDeferred<Unit>()
        lateinit var runtime: RecordingRuntime
        runtime = RecordingRuntime(
            exportAction = { _, onSaving, onCommitted ->
                val callerJob = currentCoroutineContext()[Job]!!
                onSaving()
                withContext(NonCancellable) {
                    commitEntered.complete(Unit)
                    commitRelease.await()
                    onCommitted(runtime.receipt)
                    onSaving()
                    onCommitted(runtime.receipt)
                }
                callerJob.ensureActive()
                runtime.receipt
            },
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()
        commitEntered.await()
        viewModel.cancel()
        commitRelease.complete(Unit)
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(runtime.receipt.uri, viewModel.uiState.value.finalUri)
        assertFalse(viewModel.uiState.value.runCancelRequested)
    }

    @Test
    fun `background commit callback reaches main before cancelled return is discarded`() {
        val main = Executors.newSingleThreadExecutor { task -> Thread(task, "view-model-main") }
            .asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { task -> Thread(task, "publisher-io") }
            .asCoroutineDispatcher()
        Dispatchers.setMain(main)
        try {
            runBlocking {
                val runtime = CrossDispatcherRuntime(io, main)
                val viewModel = readyViewModel(runtime)

                viewModel.createVlog()
                runtime.commitEntered.await()
                viewModel.cancel()
                runtime.commitRelease.complete(Unit)
                val terminal = withTimeout(5_000) {
                    viewModel.uiState.first { it.runState.isTerminal() }
                }

                assertSame(RunState.Succeeded, terminal.runState)
                assertSame(runtime.receipt.uri, terminal.finalUri)
                assertTrue(runtime.callbackThreadName.orEmpty().startsWith("view-model-main"))
                assertFalse(runtime.exportReturned)
            }
        } finally {
            Dispatchers.setMain(dispatcher)
            io.close()
            main.close()
        }
    }

    @Test
    fun `input rejection and sensitive exceptions expose only fixed state`() = runTest(dispatcher) {
        val sentinel = "SENSITIVE_SENTINEL_DO_NOT_EXPOSE"
        val rejected = RecordingRuntime(
            probe = validProbe.copy(readable = false),
        )
        val rejectedViewModel = readyViewModel(rejected)
        rejectedViewModel.createVlog()
        runCurrent()

        assertEquals(RejectionReason.UNREADABLE, rejectedViewModel.uiState.value.inputRejection)
        assertEquals(
            RunState.Failed(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED),
            rejectedViewModel.uiState.value.runState,
        )

        val failed = RecordingRuntime(
            pipelineAction = { _, _, _ -> throw IllegalStateException(sentinel) },
        )
        val failedViewModel = readyViewModel(failed)
        failedViewModel.createVlog()
        runCurrent()

        assertEquals(
            RunState.Failed(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED),
            failedViewModel.uiState.value.runState,
        )
        assertFalse(failedViewModel.uiState.value.toString().contains(sentinel))
    }

    @Test
    fun `real pipeline keeps diagnostics local and passes normalized JSON directly to editing`() = runTest(dispatcher) {
        val sentinel = "SENSITIVE_DIAGNOSTIC_SENTINEL"
        val runtime = RealPipelineRuntime(sentinel)
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()

        assertSame(RunState.Succeeded, viewModel.uiState.value.runState)
        assertSame(runtime.receipt.uri, viewModel.uiState.value.finalUri)
        assertEquals(2, runtime.requests.size)
        assertTrue(runtime.requests[0].parts[0] is ModelPart.InlineVideo)
        assertTrue(runtime.requests[0].parts[1] is ModelPart.Text)
        assertEquals(
            listOf(ModelPart.Text(buildEditingTask(runtime.saved.single().first))),
            runtime.requests[1].parts,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), (runtime.requests[0].parts[0] as ModelPart.InlineVideo).bytes)
        assertEquals("video/mp4", (runtime.requests[0].parts[0] as ModelPart.InlineVideo).mimeType)
        assertEquals(1, runtime.exportCalls)
        assertTrue(runtime.pipelineResult.diagnostics.segments.single().diagnostics.single().message.contains(sentinel))
        assertFalse(viewModel.uiState.value.toString().contains(sentinel))
        assertFalse(runtime.saved.single().first.contains(sentinel))
        assertFalse(runtime.saved.single().second.contains(sentinel))
        assertFalse(runtime.requests[1].parts.toString().contains(sentinel))
        assertFalse(runtime.exportedSpec.toString().contains(sentinel))
        assertFalse(runtime.saved.single().first == runtime.rawUnderstanding)
        val renderedClip = runtime.exportedSpec.clips.single()
        assertSame(runtime.inspectedSource.uri, renderedClip.uri)
        assertEquals(0L, renderedClip.startUs)
        assertEquals(1_000_000L, renderedClip.endUs)
        assertFalse(renderedClip.hasAudio)
        assertFalse(runtime.exportedSpec.expectsAudio)
    }

    @Test
    fun `real pipeline removes sensitive model failure details before state or export`() = runTest(dispatcher) {
        val sentinel = "SENSITIVE_MODEL_FAILURE_SENTINEL"
        val runtime = RealPipelineRuntime(
            sentinel = sentinel,
            modelFailure = IllegalStateException(sentinel),
        )
        val viewModel = readyViewModel(runtime)

        viewModel.createVlog()
        runCurrent()

        assertEquals(
            RunState.Failed(RunPhase.ANALYZING, RunFailureCode.UNDERSTANDING_FAILED),
            viewModel.uiState.value.runState,
        )
        assertFalse(viewModel.uiState.value.toString().contains(sentinel))
        assertEquals(1, runtime.requests.size)
        assertTrue(runtime.saved.isEmpty())
        assertEquals(0, runtime.exportCalls)
    }

    private fun readyViewModel(runtime: VlogRuntime): MainViewModel = MainViewModel(
        runtime = runtime,
        firebaseConfigured = true,
    ).also { viewModel ->
        viewModel.confirmDisclosure()
        viewModel.acceptSelection(listOf(mock(Uri::class.java)))
    }

    private class RecordingRuntime(
        private val probe: ProbeResult = validProbe,
        private val probesBySource: Map<String, ProbeResult> = emptyMap(),
        private val pipelineAction: suspend (
            List<SelectedSource>,
            Map<String, ProbeResult>,
            (RunPhase) -> Unit,
        ) -> PipelineResult = { sources, _, onPhase ->
            onPhase(RunPhase.PREPARING)
            yield()
            onPhase(RunPhase.ANALYZING)
            yield()
            onPhase(RunPhase.PLANNING)
            yield()
            pipelineResult(sources.first().sourceId)
        },
        private val exportAction: (suspend (
            RenderSpec,
            () -> Unit,
            (PublicationReceipt) -> Unit,
        ) -> PublicationReceipt)? = null,
        private val subtitleAction: (suspend (
            Uri,
            (SubtitlePhase) -> Unit,
            (PublicationReceipt) -> Unit,
        ) -> SubtitleJobResult)? = null,
    ) : VlogRuntime, SubtitleRuntime {
        val inspected = mutableListOf<SelectedSource>()
        val receipt = PublicationReceipt(mock(Uri::class.java))
        val subtitleReceipt = PublicationReceipt(mock(Uri::class.java))
        var pipelineCalls = 0
        var exportCalls = 0
        var subtitleCalls = 0
        val subtitleSources = mutableListOf<Uri>()
        var exportedSpec: RenderSpec? = null

        override suspend fun inspect(source: SelectedSource): ProbeResult {
            inspected += source
            return probesBySource[source.sourceId] ?: probe
        }

        override suspend fun runPipeline(
            sources: List<SelectedSource>,
            probes: Map<String, ProbeResult>,
            onPhase: (RunPhase) -> Unit,
        ): PipelineResult {
            pipelineCalls += 1
            return pipelineAction(sources, probes, onPhase)
        }

        override suspend fun export(
            spec: RenderSpec,
            onSaving: () -> Unit,
            onCommitted: (PublicationReceipt) -> Unit,
        ): PublicationReceipt {
            exportCalls += 1
            exportedSpec = spec
            return exportAction?.invoke(spec, onSaving, onCommitted) ?: run {
                yield()
                onSaving()
                yield()
                onCommitted(receipt)
                receipt
            }
        }

        override suspend fun addSubtitles(
            source: Uri,
            onPhase: (SubtitlePhase) -> Unit,
            onCommitted: (PublicationReceipt) -> Unit,
        ): SubtitleJobResult {
            subtitleCalls += 1
            subtitleSources += source
            return subtitleAction?.invoke(source, onPhase, onCommitted) ?: run {
                onPhase(SubtitlePhase.TRANSCRIBING)
                yield()
                onPhase(SubtitlePhase.RENDERING)
                yield()
                onPhase(SubtitlePhase.SAVING)
                onCommitted(subtitleReceipt)
                SubtitleJobResult.Published(subtitleReceipt)
            }
        }
    }

    private class CrossDispatcherRuntime(
        private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : VlogRuntime {
        val commitEntered = CompletableDeferred<Unit>()
        val commitRelease = CompletableDeferred<Unit>()
        val receipt = PublicationReceipt(mock(Uri::class.java))
        var callbackThreadName: String? = null
        var exportReturned = false

        override suspend fun inspect(source: SelectedSource): ProbeResult = validProbe

        override suspend fun runPipeline(
            sources: List<SelectedSource>,
            probes: Map<String, ProbeResult>,
            onPhase: (RunPhase) -> Unit,
        ): PipelineResult {
            onPhase(RunPhase.PREPARING)
            onPhase(RunPhase.ANALYZING)
            onPhase(RunPhase.PLANNING)
            return pipelineResult(sources.single().sourceId)
        }

        override suspend fun export(
            spec: RenderSpec,
            onSaving: () -> Unit,
            onCommitted: (PublicationReceipt) -> Unit,
        ): PublicationReceipt {
            onSaving()
            val committed = withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    commitEntered.complete(Unit)
                    commitRelease.await()
                    withContext(mainDispatcher) {
                        callbackThreadName = Thread.currentThread().name
                        onCommitted(receipt)
                    }
                    receipt
                }
            }
            exportReturned = true
            return committed
        }
    }

    private class RealPipelineRuntime(
        private val sentinel: String,
        private val modelFailure: Exception? = null,
    ) : VlogRuntime {
        val rawUnderstanding =
            """{"source_id":"video_01","segment_id":"video_01_s01","segment_duration_s":1.000,"events":[{"event_id":"event_01","start_in_segment_s":0,"end_in_segment_s":1,"continues_before":false,"continues_after":false,"description":"fixed event","audio_description":null}]}"""
        private val responses = listOf(
            rawUnderstanding,
            """{"clips":[{"event_id":"event_01","story_role":"opening","selection_reason":"fixed event"}]}""",
        )
        private val prompts = PromptRepository(object : PromptSource {
            override fun read(name: String): String = "fixed system prompt: $name"
        })
        private val model = object : OneShotModelClient {
            override suspend fun generate(request: ModelRequest): AiRawResult {
                val responseIndex = requests.size
                requests += request
                if (responseIndex == 0) modelFailure?.let { throw it }
                val response = responses[responseIndex]
                return AiRawResult(response, "test-model")
            }
        }
        private val store = object : RunStore {
            override fun saveFinalJson(understandingJson: String, editPlanJson: String): File {
                saved += understandingJson to editPlanJson
                return File("unused-test-run")
            }
        }
        val requests = mutableListOf<ModelRequest>()
        val saved = mutableListOf<Pair<String, String>>()
        val receipt = PublicationReceipt(mock(Uri::class.java))
        lateinit var pipelineResult: PipelineResult
        lateinit var exportedSpec: RenderSpec
        lateinit var inspectedSource: SelectedSource
        var exportCalls = 0

        override suspend fun inspect(source: SelectedSource): ProbeResult {
            inspectedSource = source
            return validProbe
        }

        override suspend fun runPipeline(
            sources: List<SelectedSource>,
            probes: Map<String, ProbeResult>,
            onPhase: (RunPhase) -> Unit,
        ): PipelineResult {
            pipelineResult = VlogPipeline(
                sourceBytesReader = SourceBytesReader { _, _ ->
                    SourceBytesReadResult.Fits(byteArrayOf(1, 2, 3))
                },
                sourceMetadataReader = SourceMetadataReader {
                    SourceMetadataReadResult(kotlinx.serialization.json.JsonObject(emptyMap()), emptyList())
                },
                understandingRequestFactory = UnderstandingRequestFactory(prompts),
                editingRequestFactory = EditingRequestFactory(prompts),
                modelClient = model,
                runStore = store,
                onPhase = onPhase,
                validateSegment = { segment, window ->
                    when (val validated = validateSegmentContract(segment, window)) {
                        is ValidationResult.Invalid -> validated
                        is ValidationResult.Valid -> ValidationResult.Valid(
                            validated.value,
                            validated.diagnostics + Diagnostic("private", sentinel),
                        )
                    }
                },
            ).run(sources, probes)
            return pipelineResult
        }

        override suspend fun export(
            spec: RenderSpec,
            onSaving: () -> Unit,
            onCommitted: (PublicationReceipt) -> Unit,
        ): PublicationReceipt {
            exportCalls += 1
            exportedSpec = spec
            onSaving()
            onCommitted(receipt)
            return receipt
        }
    }

    private companion object {
        val validProbe = ProbeResult(
            readable = true,
            durationUs = 1_000_000,
            resolverMimeType = "video/mp4",
            containerMimeType = "video/mp4",
            videoTrack = VideoTrackInfo("video/avc", 16, 16, 0, 30f, null, null, null),
            audioTracks = emptyList(),
            firstSyncFrameDecoded = true,
        )

        fun pipelineResult(sourceId: String): PipelineResult {
            val window = SourceWindow(
                sourceOrder = 1,
                sourceId = sourceId,
                sourceDuration = BigDecimal.ONE,
                segmentId = "${sourceId}_s01",
                segmentSourceStart = BigDecimal.ZERO,
                segmentSourceEnd = BigDecimal.ONE,
                segmentDuration = BigDecimal("1.000"),
            )
            val event = UnderstandingEvent(
                eventId = "event_01",
                startInSegment = BigDecimal.ZERO,
                endInSegment = BigDecimal.ONE,
                continuesBefore = false,
                continuesAfter = false,
                description = "fixed",
                audioDescription = null,
            )
            val editClip = EditClip(sourceId, event.eventId, BigDecimal.ZERO, BigDecimal.ONE, "opening", "fixed")
            return PipelineResult(
                understanding = VideoUnderstanding(
                    listOf(ValidatedSegment(window, listOf(ValidatedEvent(event, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE)))),
                ),
                plan = ValidatedEditPlan(listOf(ValidatedEditClip(editClip, BigDecimal.ONE)), BigDecimal.ONE),
                diagnostics = PipelineDiagnostics(
                    segments = emptyList(),
                    editPlan = listOf(Diagnostic("private", "must stay local")),
                ),
            )
        }
    }
}
