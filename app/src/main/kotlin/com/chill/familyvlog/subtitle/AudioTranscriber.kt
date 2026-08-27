package com.chill.familyvlog.subtitle

import com.chill.familyvlog.render.RenderClip
import java.util.concurrent.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface AudioTranscriber {
    suspend fun transcribe(source: SubtitleSource): List<TranscriptCue>
}

fun interface AudioIntervalDecoder {
    suspend fun decode(clip: RenderClip, consume: suspend (FloatArray) -> Unit)
}

data class SpeechSegmentSamples(
    val startSample: Int,
    val samples: FloatArray,
)

data class SpeechRecognition(
    val language: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
)

fun interface SpeechSessionFactory {
    fun create(): SpeechSession
}

interface SpeechSession : AutoCloseable {
    fun reset()
    fun accept(samples: FloatArray): List<SpeechSegmentSamples>
    fun flush(): List<SpeechSegmentSamples>
    fun recognize(samples: FloatArray): SpeechRecognition
}

class LocalAudioTranscriber(
    private val decoder: AudioIntervalDecoder,
    private val sessionFactory: SpeechSessionFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AudioTranscriber {
    override suspend fun transcribe(source: SubtitleSource): List<TranscriptCue> {
        currentCoroutineContext().ensureActive()
        if (!source.hasAudio) return emptyList()
        require(source.durationUs > 0)
        return try {
            withContext(dispatcher) {
                val cues = mutableListOf<TranscriptCue>()
                currentCoroutineContext().ensureActive()
                val session = sessionFactory.create()
                var sessionFailure: Throwable? = null
                try {
                    currentCoroutineContext().ensureActive()
                    session.reset()
                    currentCoroutineContext().ensureActive()
                    val clip = RenderClip(source.uri, 0, source.durationUs, hasAudio = true)
                    decoder.decode(clip) { samples ->
                        currentCoroutineContext().ensureActive()
                        val segments = session.accept(samples)
                        currentCoroutineContext().ensureActive()
                        segments.forEach { segment ->
                            addCue(session, segment, source.durationUs, cues)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val tailSegments = session.flush()
                    currentCoroutineContext().ensureActive()
                    tailSegments.forEach { segment ->
                        currentCoroutineContext().ensureActive()
                        addCue(session, segment, source.durationUs, cues)
                    }
                } catch (failure: Throwable) {
                    sessionFailure = failure
                    throw failure
                } finally {
                    val closeFailure = try {
                        session.close()
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                    cleanupFailureToThrow(sessionFailure, closeFailure)?.let { throw it }
                }
                cues
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LinkageError) {
            throw transcriptionFailure(failure)
        } catch (failure: Exception) {
            throw transcriptionFailure(failure)
        }
    }

    private suspend fun addCue(
        session: SpeechSession,
        segment: SpeechSegmentSamples,
        sourceDurationUs: Long,
        output: MutableList<TranscriptCue>,
    ) {
        currentCoroutineContext().ensureActive()
        require(segment.startSample >= 0)
        val startUs = samplesToMicroseconds(segment.startSample.toLong())
        if (startUs >= sourceDurationUs || segment.samples.isEmpty()) return
        val rawEndUs = samplesToMicroseconds(segment.startSample.toLong() + segment.samples.size)
        val endUs = minOf(rawEndUs, sourceDurationUs)
        if (endUs <= startUs) return
        val recognition = session.recognize(segment.samples)
        currentCoroutineContext().ensureActive()
        val language = try {
            recognition.language.toSubtitleLanguage()
        } catch (failure: IllegalArgumentException) {
            logRecognitionFailure(
                recognition = recognition,
                operation = SubtitleDiagnosticOperation.LANGUAGE_MAPPING,
                timestampRule = SubtitleTimestampRule.NOT_APPLICABLE,
                failure = failure,
            )
            throw failure
        }
        if (recognition.tokens.isEmpty() && recognition.timestampsSeconds.isEmpty()) return
        requireRecognition(
            condition = recognition.tokens.size == recognition.timestampsSeconds.size,
            recognition = recognition,
            timestampRule = SubtitleTimestampRule.TOKEN_COUNT_MATCH,
        )
        val timedTokens = mutableListOf<TimedToken>()
        var pendingBoundaryStartUs: Long? = null
        var pendingWordBoundary = false
        recognition.tokens.indices.forEach { index ->
            val token = recognition.tokens[index]
            val relativeStartSeconds = recognition.timestampsSeconds[index]
            requireRecognition(
                condition = relativeStartSeconds.isFinite() && relativeStartSeconds >= 0f,
                recognition = recognition,
                timestampRule = SubtitleTimestampRule.FINITE_NONNEGATIVE,
            )
            val relativeStartUs = (relativeStartSeconds.toDouble() * 1_000_000.0).roundToLong()
            val tokenStartUs = Math.addExact(startUs, relativeStartUs)
            val tokenEndUs = if (index + 1 < recognition.tokens.size) {
                val nextSeconds = recognition.timestampsSeconds[index + 1]
                requireRecognition(
                    condition = nextSeconds.isFinite() && nextSeconds > relativeStartSeconds,
                    recognition = recognition,
                    timestampRule = SubtitleTimestampRule.STRICTLY_INCREASING,
                )
                Math.addExact(
                    startUs,
                    (nextSeconds.toDouble() * 1_000_000.0).roundToLong(),
                )
            } else {
                endUs
            }
            requireRecognition(
                condition = tokenStartUs >= startUs && tokenEndUs > tokenStartUs && tokenEndUs <= endUs,
                recognition = recognition,
                timestampRule = SubtitleTimestampRule.WITHIN_VAD_SEGMENT,
            )
            if (token.replace(SENTENCE_PIECE_WORD_BOUNDARY, ' ').isBlank()) {
                if (pendingBoundaryStartUs == null) pendingBoundaryStartUs = tokenStartUs
                pendingWordBoundary = pendingWordBoundary || token.contains(SENTENCE_PIECE_WORD_BOUNDARY)
            } else {
                val normalizedToken = if (
                    pendingWordBoundary && !token.startsWith(SENTENCE_PIECE_WORD_BOUNDARY)
                ) {
                    "$SENTENCE_PIECE_WORD_BOUNDARY$token"
                } else {
                    token
                }
                timedTokens += TimedToken(
                    startUs = pendingBoundaryStartUs ?: tokenStartUs,
                    endUs = tokenEndUs,
                    text = normalizedToken,
                )
                pendingBoundaryStartUs = null
                pendingWordBoundary = false
            }
        }
        if (timedTokens.isNotEmpty()) {
            val previousEndUs = output.lastOrNull()?.tokens?.last()?.endUs ?: 0L
            requireRecognition(
                condition = timedTokens.first().startUs >= previousEndUs,
                recognition = recognition,
                timestampRule = SubtitleTimestampRule.CUES_NONOVERLAP,
            )
            output += TranscriptCue(language, timedTokens)
        }
    }
}

private fun requireRecognition(
    condition: Boolean,
    recognition: SpeechRecognition,
    timestampRule: SubtitleTimestampRule,
) {
    if (condition) return
    val failure = IllegalArgumentException("invalid_speech_recognition_result")
    logRecognitionFailure(
        recognition = recognition,
        operation = SubtitleDiagnosticOperation.TOKEN_VALIDATION,
        timestampRule = timestampRule,
        failure = failure,
    )
    throw failure
}

private fun logRecognitionFailure(
    recognition: SpeechRecognition,
    operation: SubtitleDiagnosticOperation,
    timestampRule: SubtitleTimestampRule,
    failure: Throwable,
) {
    logSubtitleDiagnostic(
        subtitleDiagnostic(
            operation = operation,
            failure = failure,
            language = recognition.language,
            tokenCount = recognition.tokens.size,
            timestampCount = recognition.timestampsSeconds.size,
            timestampRule = timestampRule,
        ),
    )
}

private fun samplesToMicroseconds(samples: Long): Long = Math.multiplyExact(samples, 1_000_000L) / 16_000L

private const val SENTENCE_PIECE_WORD_BOUNDARY = '▁'

private fun String.toSubtitleLanguage(): SubtitleLanguage = when (
    lowercase().trim().removeSurrounding("<|", "|>")
) {
    "zh", "zh-cn", "cmn" -> SubtitleLanguage.CHINESE
    "en", "en-us", "en-gb" -> SubtitleLanguage.ENGLISH
    else -> throw IllegalArgumentException("unsupported_subtitle_language")
}

internal fun transcriptionFailure(cause: Throwable? = null): SubtitleException =
    SubtitleException(
        phase = SubtitlePhase.TRANSCRIBING,
        code = SubtitleFailureCode.TRANSCRIPTION_FAILED,
        cause = cause,
    )
