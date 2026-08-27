package com.chill.familyvlog.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSubtitleDocumentTest {
    @Test
    fun `document freezes approved styles and emits paired single-line events`() {
        val document = buildAssSubtitleDocument(
            videoWidth = 720,
            videoHeight = 1_280,
            cues = listOf(
                BilingualCaptionCue(
                    startUs = 1_234_567,
                    endUs = 2_345_678,
                    chinese = "妹妹在吃饭。",
                    english = "She is eating.",
                ),
            ),
        )

        assertTrue(document.contains("PlayResX: 720\nPlayResY: 1280"))
        assertTrue(document.contains("WrapStyle: 2"))
        assertTrue(document.contains("ScaledBorderAndShadow: yes"))
        assertTrue(document.contains(
            "Style: Chinese,Noto Sans CJK SC,42,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,-1,0,0,0,100,100,0,0,3,10.4,0,2,24,24,186,1",
        ))
        assertTrue(document.contains(
            "Style: English,Noto Sans CJK SC,30,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,-1,0,0,0,100,100,0,0,3,10.4,0,2,24,24,128,1",
        ))
        val dialogues = document.lineSequence().filter { it.startsWith("Dialogue:") }.toList()
        assertEquals(2, dialogues.size)
        assertTrue(dialogues[0].contains(",Chinese,"))
        assertTrue(dialogues[1].contains(",English,"))
        assertEquals(dialogues[0].split(',')[1], dialogues[1].split(',')[1])
        assertEquals(dialogues[0].split(',')[2], dialogues[1].split(',')[2])
        assertEquals("0:00:01.23", dialogues[0].split(',')[1])
        assertEquals("0:00:02.35", dialogues[0].split(',')[2])
        assertFalse(document.contains("\\N"))
    }

    @Test
    fun `adjacent raw cues share one quantized boundary without overlap`() {
        val dialogues = buildAssSubtitleDocument(
            videoWidth = 720,
            videoHeight = 1_280,
            cues = listOf(
                BilingualCaptionCue(1, 10_001, "一", "one"),
                BilingualCaptionCue(10_001, 20_001, "二", "two"),
            ),
        ).lineSequence().filter { it.startsWith("Dialogue:") }.toList()

        val firstEnd = dialogues[0].split(',')[2]
        val secondStart = dialogues[2].split(',')[1]
        assertEquals("0:00:00.02", firstEnd)
        assertEquals(firstEnd, secondStart)
    }

    @Test
    fun `document uses rounded aspect resolution and rejects ass controls`() {
        assertTrue(
            buildAssSubtitleDocument(
                videoWidth = 1_920,
                videoHeight = 1_080,
                cues = listOf(BilingualCaptionCue(0, 10_000, "中文", "English")),
            ).contains("PlayResX: 2276\nPlayResY: 1280"),
        )

        listOf("line\\break", "{tag}", "line\nbreak", "line\rbreak").forEach { invalid ->
            val failure = runCatching {
                buildAssSubtitleDocument(
                    videoWidth = 720,
                    videoHeight = 1_280,
                    cues = listOf(BilingualCaptionCue(0, 10_000, invalid, "English")),
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
}
