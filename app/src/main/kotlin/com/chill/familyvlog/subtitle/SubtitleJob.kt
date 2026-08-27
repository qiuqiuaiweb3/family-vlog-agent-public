package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.output.PublicationReceipt
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface SubtitleSourceInspector {
    suspend fun inspect(uri: Uri): SubtitleSource
}

fun interface CaptionTranslatorFactory {
    fun create(): CaptionTranslatorSession
}

interface CaptionTranslatorSession : AutoCloseable {
    suspend fun prepare(onDownloadRequired: () -> Unit)

    suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String
}

fun interface BilingualCaptionPlanner {
    suspend fun plan(
        transcript: List<TranscriptCue>,
        translator: CaptionTranslatorSession,
        videoWidth: Int,
        videoHeight: Int,
    ): List<BilingualCaptionCue>
}

fun interface AssSubtitleBuilder {
    fun build(
        videoWidth: Int,
        videoHeight: Int,
        cues: List<BilingualCaptionCue>,
    ): String
}

fun interface SubtitleExporter {
    suspend fun export(
        source: SubtitleSource,
        assDocument: String,
        onSaving: () -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): PublicationReceipt
}

class SubtitleJob(
    private val inspector: SubtitleSourceInspector,
    private val transcriber: AudioTranscriber,
    private val translatorFactory: CaptionTranslatorFactory,
    private val planner: BilingualCaptionPlanner,
    private val assBuilder: AssSubtitleBuilder,
    private val exporter: SubtitleExporter,
) {
    suspend fun run(
        sourceUri: Uri,
        onPhase: (SubtitlePhase) -> Unit,
        onCommitted: (PublicationReceipt) -> Unit,
    ): SubtitleJobResult {
        currentCoroutineContext().ensureActive()
        onPhase(SubtitlePhase.TRANSCRIBING)
        val source = subtitleBoundary(
            SubtitlePhase.TRANSCRIBING,
            SubtitleFailureCode.TRANSCRIPTION_FAILED,
        ) {
            inspector.inspect(sourceUri).also { require(it.uri == sourceUri) }
        }
        if (!source.hasAudio) return SubtitleJobResult.NoSpeech

        val transcript = subtitleBoundary(
            SubtitlePhase.TRANSCRIBING,
            SubtitleFailureCode.TRANSCRIPTION_FAILED,
        ) {
            transcriber.transcribe(source)
        }
        if (transcript.isEmpty()) return SubtitleJobResult.NoSpeech

        val translator = subtitleBoundary(
            SubtitlePhase.DOWNLOADING_MODEL,
            SubtitleFailureCode.MODEL_DOWNLOAD_FAILED,
        ) {
            translatorFactory.create()
        }
        var translatorPhase = SubtitlePhase.DOWNLOADING_MODEL
        var primaryFailure: Throwable? = null
        val captions = try {
            subtitleBoundary(
                SubtitlePhase.DOWNLOADING_MODEL,
                SubtitleFailureCode.MODEL_DOWNLOAD_FAILED,
            ) {
                translator.prepare { onPhase(SubtitlePhase.DOWNLOADING_MODEL) }
            }
            currentCoroutineContext().ensureActive()
            onPhase(SubtitlePhase.TRANSLATING)
            translatorPhase = SubtitlePhase.TRANSLATING
            subtitleBoundary(
                SubtitlePhase.TRANSLATING,
                SubtitleFailureCode.TRANSLATION_FAILED,
            ) {
                planner.plan(
                    transcript = transcript,
                    translator = translator,
                    videoWidth = source.videoWidth,
                    videoHeight = source.videoHeight,
                )
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val closeFailure = try {
                translator.close()
                null
            } catch (failure: Throwable) {
                failure
            }
            cleanupFailureToThrow(primaryFailure, closeFailure)?.let { failure ->
                throw failure.toSubtitleException(translatorPhase)
            }
        }
        val assDocument = subtitleBoundary(
            SubtitlePhase.TRANSLATING,
            SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
        ) {
            require(captions.isNotEmpty())
            assBuilder.build(source.videoWidth, source.videoHeight, captions)
        }

        currentCoroutineContext().ensureActive()
        onPhase(SubtitlePhase.RENDERING)
        val receipt = subtitleBoundary(
            SubtitlePhase.RENDERING,
            SubtitleFailureCode.RENDER_FAILED,
        ) {
            exporter.export(
                source = source,
                assDocument = assDocument,
                onSaving = { onPhase(SubtitlePhase.SAVING) },
                onCommitted = onCommitted,
            )
        }
        return SubtitleJobResult.Published(receipt)
    }
}

private suspend inline fun <T> subtitleBoundary(
    phase: SubtitlePhase,
    code: SubtitleFailureCode,
    crossinline block: suspend () -> T,
): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: SubtitleException) {
    throw failure
} catch (failure: LinkageError) {
    throw SubtitleException(phase, code, failure)
} catch (failure: Exception) {
    throw SubtitleException(phase, code, failure)
}

private fun Throwable.toSubtitleException(phase: SubtitlePhase): SubtitleException =
    if (this is SubtitleException) {
        this
    } else {
        SubtitleException(
            phase = phase,
            code = when (phase) {
                SubtitlePhase.DOWNLOADING_MODEL -> SubtitleFailureCode.MODEL_DOWNLOAD_FAILED
                SubtitlePhase.TRANSLATING -> SubtitleFailureCode.TRANSLATION_FAILED
                SubtitlePhase.TRANSCRIBING -> SubtitleFailureCode.TRANSCRIPTION_FAILED
                SubtitlePhase.RENDERING -> SubtitleFailureCode.RENDER_FAILED
                SubtitlePhase.SAVING -> SubtitleFailureCode.PUBLISH_FAILED
            },
            cause = this,
        )
    }
