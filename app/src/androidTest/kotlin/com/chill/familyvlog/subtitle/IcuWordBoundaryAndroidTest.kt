package com.chill.familyvlog.subtitle

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IcuWordBoundaryAndroidTest {
    @Test
    fun englishSentencepieceWordIsNotSplitInsideItsIcuBoundary() = runBlocking {
        val planner = LocalBilingualCaptionPlanner(
            measurer = SingleLineTextMeasurer { text, _ ->
                if (text == "playing now") 10_000f else 1f
            },
        )
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) = Unit

            override suspend fun translate(
                sourceLanguage: SubtitleLanguage,
                text: String,
            ): String = when (text) {
                "playing now" -> "正在播放"
                "playing" -> "播放"
                "now" -> "现在"
                else -> error("unexpected_text")
            }

            override fun close() = Unit
        }

        val result = planner.plan(
            transcript = listOf(
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(
                        TimedToken(0, 100, "▁play"),
                        TimedToken(100, 200, "ing"),
                        TimedToken(200, 300, "▁now"),
                    ),
                ),
            ),
            translator = translator,
            videoWidth = 720,
            videoHeight = 1_280,
        )

        assertEquals(listOf(0L to 200L, 200L to 300L), result.map { it.startUs to it.endUs })
    }
}
