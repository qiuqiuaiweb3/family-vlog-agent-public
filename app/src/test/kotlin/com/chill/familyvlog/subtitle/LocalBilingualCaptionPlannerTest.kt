package com.chill.familyvlog.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class LocalBilingualCaptionPlannerTest {
    @Test
    fun `Chinese English Chinese cues use both translation directions in source order`() = runTest {
        val translator = RecordingTranslator { language, text ->
            when (language) {
                SubtitleLanguage.CHINESE -> when (text) {
                    "你好" -> "hello"
                    "再见" -> "goodbye"
                    else -> error("unexpected Chinese source")
                }
                SubtitleLanguage.ENGLISH -> when (text) {
                    "world" -> "世界"
                    else -> error("unexpected English source")
                }
            }
        }
        val planner = planner(measure = { _, _ -> 1f })

        val result = planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(token(0, 100, "你好")),
                ),
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(token(200, 300, "▁world")),
                ),
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(token(400, 500, "再见")),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(
            listOf(
                SubtitleLanguage.CHINESE to "你好",
                SubtitleLanguage.ENGLISH to "world",
                SubtitleLanguage.CHINESE to "再见",
            ),
            translator.calls,
        )
        assertEquals(
            listOf(
                BilingualCaptionCue(0, 100, "你好", "hello"),
                BilingualCaptionCue(200, 300, "世界", "world"),
                BilingualCaptionCue(400, 500, "再见", "goodbye"),
            ),
            result,
        )
    }

    @Test
    fun `English sentencepiece suffixes are reconstructed without invented spaces`() = runTest {
        val translator = RecordingTranslator { _, _ -> "正在播放" }
        val planner = planner(measure = { _, _ -> 1f })

        val result = planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(
                        token(0, 100, "▁play"),
                        token(100, 200, "ing"),
                        token(200, 300, "▁now"),
                    ),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(listOf(SubtitleLanguage.ENGLISH to "playing now"), translator.calls)
        assertEquals(
            listOf(BilingualCaptionCue(0, 300, "正在播放", "playing now")),
            result,
        )
    }

    @Test
    fun `punctuation and pause split first while preserving token time order`() = runTest {
        val translator = RecordingTranslator { _, text -> "E:$text" }
        val planner = planner(measure = { _, _ -> 1f })

        val result = planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(
                        token(0, 100, "你"),
                        token(100, 200, "好，"),
                        token(800_000, 900_000, "再"),
                        token(900_000, 1_000_000, "见"),
                    ),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(listOf("你好，", "再见"), translator.calls.map(Pair<*, String>::second))
        assertEquals(listOf(0L to 200L, 800_000L to 1_000_000L), result.map { it.startUs to it.endUs })
    }

    @Test
    fun `English full stop is a natural boundary before width splitting`() = runTest {
        val translator = RecordingTranslator { _, text -> "中:$text" }
        val planner = planner(measure = { _, _ -> 1f })

        planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(
                        token(0, 100, "▁Hello"),
                        token(100, 200, "."),
                        token(200, 300, "▁Again"),
                    ),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(listOf("Hello.", "Again"), translator.calls.map(Pair<*, String>::second))
    }

    @Test
    fun `overflow splits only at ICU word boundary and never inside a sentencepiece word`() = runTest {
        val translator = RecordingTranslator { _, text -> if (text == "playing") "播放" else "现在" }
        val planner = planner(
            measure = { text, _ -> if (text.contains(' ')) 10_000f else 1f },
            boundaries = { text, _ -> when (text) {
                "playing now" -> setOf(0, 7, 8, 11)
                else -> setOf(0, text.length)
            } },
        )

        val result = planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(
                        token(0, 100, "▁play"),
                        token(100, 200, "ing"),
                        token(200, 300, "▁now"),
                    ),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(listOf("playing now", "playing", "now"), translator.calls.map(Pair<*, String>::second))
        assertEquals(listOf(0L to 200L, 200L to 300L), result.map { it.startUs to it.endUs })
    }

    @Test
    fun `single token or group without an ICU boundary fails instead of shrinking or wrapping`() = runTest {
        val planner = planner(
            measure = { _, _ -> 10_000f },
            boundaries = { text, _ -> setOf(0, text.length) },
        )
        listOf(
            listOf(token(0, 100, "很长")),
            listOf(token(0, 100, "▁play"), token(100, 200, "ing")),
        ).forEach { tokens ->
            val failure = runCatching {
                planner.plan(
                    listOf(TranscriptCue(SubtitleLanguage.CHINESE, tokens)),
                    RecordingTranslator { _, _ -> "also long" },
                    720,
                    1_280,
                )
            }.exceptionOrNull()

            assertTrue(failure is SubtitleException)
            failure as SubtitleException
            assertEquals(SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED, failure.code)
        }
    }

    private fun planner(
        measure: (String, SubtitleStyle) -> Float,
        boundaries: (String, SubtitleLanguage) -> Set<Int> = { text, _ ->
            (0..text.length).toSet()
        },
    ) = LocalBilingualCaptionPlanner(
        measurer = SingleLineTextMeasurer(measure),
        wordBoundaries = WordBoundaryResolver(boundaries),
    )

    private fun token(startUs: Long, endUs: Long, text: String) = TimedToken(startUs, endUs, text)

    private class RecordingTranslator(
        private val response: (SubtitleLanguage, String) -> String,
    ) : CaptionTranslatorSession {
        val calls = mutableListOf<Pair<SubtitleLanguage, String>>()

        override suspend fun prepare(onDownloadRequired: () -> Unit) = Unit

        override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String {
            calls += sourceLanguage to text
            return response(sourceLanguage, text)
        }

        override fun close() = Unit
    }
}
