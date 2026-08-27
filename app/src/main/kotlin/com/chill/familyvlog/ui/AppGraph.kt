package com.chill.familyvlog.ui

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.chill.familyvlog.ai.AdkModelClient
import com.chill.familyvlog.ai.AndroidSourceBytesReader
import com.chill.familyvlog.ai.AssetManagerPromptSource
import com.chill.familyvlog.ai.EditingRequestFactory
import com.chill.familyvlog.ai.PromptRepository
import com.chill.familyvlog.ai.UnderstandingRequestFactory
import com.chill.familyvlog.analysis.AndroidSourceMetadataReader
import com.chill.familyvlog.input.AndroidMediaProbe
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.output.ExportCoordinator
import com.chill.familyvlog.output.PublicationReceipt
import com.chill.familyvlog.pipeline.AndroidAnalysisMediaAdapter
import com.chill.familyvlog.pipeline.PipelineResult
import com.chill.familyvlog.pipeline.PrivateRunStore
import com.chill.familyvlog.pipeline.RequestBoundaryAnalysisInputProcessor
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.pipeline.VlogPipeline
import com.chill.familyvlog.render.Media3Renderer
import com.chill.familyvlog.render.RenderSpec
import com.chill.familyvlog.subtitle.AndroidAudioIntervalDecoder
import com.chill.familyvlog.subtitle.AndroidSubtitleSourceInspector
import com.chill.familyvlog.subtitle.LocalAudioTranscriber
import com.chill.familyvlog.subtitle.LocalBilingualCaptionPlanner
import com.chill.familyvlog.subtitle.Media3SubtitleExporter
import com.chill.familyvlog.subtitle.MlKitCaptionTranslatorFactory
import com.chill.familyvlog.subtitle.NotoSubtitleTextMeasurer
import com.chill.familyvlog.subtitle.SherpaSpeechSessionFactory
import com.chill.familyvlog.subtitle.SubtitleJob
import com.chill.familyvlog.subtitle.SubtitleJobResult
import com.chill.familyvlog.subtitle.SubtitleException
import com.chill.familyvlog.subtitle.SubtitleFailureCode
import com.chill.familyvlog.subtitle.SubtitlePhase
import com.chill.familyvlog.subtitle.buildAssSubtitleDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface VlogRuntime {
    suspend fun inspect(source: SelectedSource): ProbeResult

    suspend fun runPipeline(
        sources: List<SelectedSource>,
        probes: Map<String, ProbeResult>,
        onPhase: (RunPhase) -> Unit,
    ): PipelineResult

    suspend fun export(
        spec: RenderSpec,
        onSaving: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt
}

internal interface SubtitleRuntime {
    suspend fun addSubtitles(
        source: android.net.Uri,
        onPhase: (SubtitlePhase) -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): SubtitleJobResult
}

@UnstableApi
internal class AppGraph(context: Context) : VlogRuntime, SubtitleRuntime {
    private val applicationContext = context.applicationContext
    private val mediaProbe = AndroidMediaProbe(applicationContext)
    private val prompts = PromptRepository(AssetManagerPromptSource(applicationContext.assets))
    private val sourceBytesReader = AndroidSourceBytesReader(applicationContext.contentResolver)
    private val sourceMetadataReader = AndroidSourceMetadataReader(applicationContext)
    private val modelClient = AdkModelClient()
    private val runStore = PrivateRunStore(applicationContext)
    private val subtitleJob by lazy {
        SubtitleJob(
            inspector = AndroidSubtitleSourceInspector(mediaProbe),
            transcriber = LocalAudioTranscriber(
                decoder = AndroidAudioIntervalDecoder(applicationContext),
                sessionFactory = SherpaSpeechSessionFactory(applicationContext.assets),
            ),
            translatorFactory = MlKitCaptionTranslatorFactory(),
            planner = LocalBilingualCaptionPlanner(
                NotoSubtitleTextMeasurer(applicationContext.assets),
            ),
            assBuilder = com.chill.familyvlog.subtitle.AssSubtitleBuilder(::buildAssSubtitleDocument),
            exporter = Media3SubtitleExporter(applicationContext),
        )
    }

    override suspend fun inspect(source: SelectedSource): ProbeResult = mediaProbe.inspect(source.uri)

    override suspend fun runPipeline(
        sources: List<SelectedSource>,
        probes: Map<String, ProbeResult>,
        onPhase: (RunPhase) -> Unit,
    ): PipelineResult {
        val understandingRequestFactory = UnderstandingRequestFactory(prompts)
        return VlogPipeline(
            sourceBytesReader = sourceBytesReader,
            sourceMetadataReader = sourceMetadataReader,
            understandingRequestFactory = understandingRequestFactory,
            editingRequestFactory = EditingRequestFactory(prompts),
            modelClient = modelClient,
            runStore = runStore,
            onPhase = onPhase,
            analysisInputProcessor = RequestBoundaryAnalysisInputProcessor(
                sourceBytesReader = sourceBytesReader,
                sourceMetadataReader = sourceMetadataReader,
                requestFactory = understandingRequestFactory,
                mediaAdapter = AndroidAnalysisMediaAdapter(applicationContext),
            ),
        ).run(sources, probes)
    }

    override suspend fun export(
        spec: RenderSpec,
        onSaving: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt = ExportCoordinator(
        context = applicationContext,
        renderer = Media3Renderer(applicationContext),
        onSaving = onSaving,
        onCommitted = onCommitted,
    ).export(spec)

    override suspend fun addSubtitles(
        source: android.net.Uri,
        onPhase: (SubtitlePhase) -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): SubtitleJobResult = withContext(Dispatchers.Default) {
        val currentJob = try {
            subtitleJob
        } catch (failure: LinkageError) {
            throw SubtitleException(
                SubtitlePhase.TRANSLATING,
                SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
                failure,
            )
        } catch (failure: Exception) {
            throw SubtitleException(
                SubtitlePhase.TRANSLATING,
                SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
                failure,
            )
        }
        currentJob.run(source, onPhase, onCommitted)
    }
}
