package com.chill.familyvlog.subtitle

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SubtitleStyle { CHINESE, ENGLISH }

fun interface SingleLineTextMeasurer {
    fun measure(text: String, style: SubtitleStyle): Float
}

fun interface WordBoundaryResolver {
    fun boundaries(text: String, language: SubtitleLanguage): Set<Int>
}

object IcuWordBoundaryResolver : WordBoundaryResolver {
    override fun boundaries(text: String, language: SubtitleLanguage): Set<Int> {
        val locale = when (language) {
            SubtitleLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            SubtitleLanguage.ENGLISH -> Locale.ENGLISH
        }
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        return buildSet {
            var boundary = iterator.first()
            while (boundary != BreakIterator.DONE) {
                add(boundary)
                boundary = iterator.next()
            }
        }
    }
}

class NotoSubtitleTextMeasurer(assetManager: AssetManager) : SingleLineTextMeasurer {
    private val typeface = Typeface.createFromAsset(assetManager, SUBTITLE_FONT_ASSET)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = this@NotoSubtitleTextMeasurer.typeface }

    override fun measure(text: String, style: SubtitleStyle): Float {
        paint.textSize = when (style) {
            SubtitleStyle.CHINESE -> 42f
            SubtitleStyle.ENGLISH -> 30f
        }
        return paint.measureText(text)
    }
}

class LocalBilingualCaptionPlanner(
    private val measurer: SingleLineTextMeasurer,
    private val wordBoundaries: WordBoundaryResolver = IcuWordBoundaryResolver,
) : BilingualCaptionPlanner {
    override suspend fun plan(
        transcript: List<TranscriptCue>,
        translator: CaptionTranslatorSession,
        videoWidth: Int,
        videoHeight: Int,
    ): List<BilingualCaptionCue> {
        require(videoWidth > 0 && videoHeight > 0 && transcript.isNotEmpty())
        val availableWidth = ((videoWidth.toDouble() * PLAY_RES_Y / videoHeight).roundToInt() - 48)
            .coerceAtLeast(1)
            .toFloat()
        validateTranscript(transcript)
        return try {
            buildList {
                transcript.forEach { cue ->
                    splitAtNaturalBoundaries(cue.tokens).forEach { tokens ->
                        addAll(planGroup(cue.language, tokens, translator, availableWidth))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SubtitleException) {
            throw failure
        } catch (failure: Exception) {
            throw SubtitleException(
                SubtitlePhase.TRANSLATING,
                SubtitleFailureCode.TRANSLATION_FAILED,
                failure,
            )
        }
    }

    private suspend fun planGroup(
        language: SubtitleLanguage,
        tokens: List<TimedToken>,
        translator: CaptionTranslatorSession,
        availableWidth: Float,
    ): List<BilingualCaptionCue> {
        val sourceText = joinTokens(language, tokens)
        val translated = translator.translate(language, sourceText).trim()
        if (translated.isEmpty()) throw translationFailure()
        val chinese = if (language == SubtitleLanguage.CHINESE) sourceText else translated
        val english = if (language == SubtitleLanguage.ENGLISH) sourceText else translated
        if (fits(chinese, english, availableWidth)) {
            return listOf(
                BilingualCaptionCue(
                    startUs = tokens.first().startUs,
                    endUs = tokens.last().endUs,
                    chinese = chinese,
                    english = english,
                ),
            )
        }
        if (tokens.size == 1) throw layoutFailure()
        val splitIndex = chooseWordBoundary(language, tokens) ?: throw layoutFailure()
        return planGroup(language, tokens.subList(0, splitIndex), translator, availableWidth) +
            planGroup(language, tokens.subList(splitIndex, tokens.size), translator, availableWidth)
    }

    private fun chooseWordBoundary(
        language: SubtitleLanguage,
        tokens: List<TimedToken>,
    ): Int? {
        val rendered = renderTokens(language, tokens)
        val boundaries = wordBoundaries.boundaries(rendered.text, language)
        val candidates = rendered.tokenEndOffsets.dropLast(1)
            .mapIndexedNotNull { index, offset -> if (offset in boundaries) index + 1 else null }
        val midpoint = rendered.text.length / 2
        return candidates.minWithOrNull(
            compareBy<Int> { abs(rendered.tokenEndOffsets[it - 1] - midpoint) }
                .thenBy { it },
        )
    }

    private fun fits(chinese: String, english: String, availableWidth: Float): Boolean =
        chinese.none(Char::isLineBreak) &&
            english.none(Char::isLineBreak) &&
            measurer.measure(chinese, SubtitleStyle.CHINESE) <= availableWidth &&
            measurer.measure(english, SubtitleStyle.ENGLISH) <= availableWidth
}

private fun validateTranscript(transcript: List<TranscriptCue>) {
    var previousEndUs = 0L
    transcript.forEach { cue ->
        require(cue.tokens.isNotEmpty())
        cue.tokens.forEach { token ->
            require(
                token.startUs >= previousEndUs &&
                    token.endUs > token.startUs &&
                    token.text.isNotBlank(),
            )
            previousEndUs = token.endUs
        }
    }
}

private fun splitAtNaturalBoundaries(tokens: List<TimedToken>): List<List<TimedToken>> {
    val groups = mutableListOf<List<TimedToken>>()
    var start = 0
    tokens.indices.forEach { index ->
        val current = tokens[index]
        val next = tokens.getOrNull(index + 1)
        val punctuationBoundary = current.text.any { it in STRONG_OR_WEAK_PUNCTUATION }
        val pauseBoundary = next != null && next.startUs - current.endUs >= PAUSE_BOUNDARY_US
        if (punctuationBoundary || pauseBoundary || next == null) {
            groups += tokens.subList(start, index + 1)
            start = index + 1
        }
    }
    return groups
}

private data class RenderedTokens(
    val text: String,
    val tokenEndOffsets: List<Int>,
)

private fun joinTokens(language: SubtitleLanguage, tokens: List<TimedToken>): String =
    renderTokens(language, tokens).text

private fun renderTokens(
    language: SubtitleLanguage,
    tokens: List<TimedToken>,
): RenderedTokens {
    val text = StringBuilder()
    val tokenEnds = ArrayList<Int>(tokens.size)
    tokens.forEachIndexed { index, token ->
        val piece = when (language) {
            SubtitleLanguage.CHINESE -> token.text.replace(SENTENCE_PIECE_WORD_BOUNDARY.toString(), "")
            SubtitleLanguage.ENGLISH -> englishTokenPiece(token.text, index == 0)
        }
        require(piece.isNotEmpty())
        text.append(piece)
        tokenEnds += text.length
    }
    return RenderedTokens(text.toString(), tokenEnds)
}

private fun englishTokenPiece(raw: String, first: Boolean): String {
    var piece = raw.replace(SENTENCE_PIECE_WORD_BOUNDARY, ' ')
    piece = MULTIPLE_SPACES.replace(piece, " ")
    if (first) piece = piece.trimStart()
    return piece
}

private fun Char.isLineBreak(): Boolean = this == '\r' || this == '\n'

private fun translationFailure() = SubtitleException(
    SubtitlePhase.TRANSLATING,
    SubtitleFailureCode.TRANSLATION_FAILED,
)

private fun layoutFailure() = SubtitleException(
    SubtitlePhase.TRANSLATING,
    SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
)

const val SUBTITLE_FONT_ASSET = "fonts/NotoSansCJKsc-Bold.otf"
private const val PLAY_RES_Y = 1_280.0
private const val PAUSE_BOUNDARY_US = 500_000L
private const val STRONG_OR_WEAK_PUNCTUATION = "。！？!?，,；;：:.…、"
private const val SENTENCE_PIECE_WORD_BOUNDARY = '▁'
private val MULTIPLE_SPACES = Regex(" +")
