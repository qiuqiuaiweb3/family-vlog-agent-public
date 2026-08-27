package com.chill.familyvlog.subtitle

import java.util.Locale
import kotlin.math.roundToInt

fun buildAssSubtitleDocument(
    videoWidth: Int,
    videoHeight: Int,
    cues: List<BilingualCaptionCue>,
): String {
    require(videoWidth > 0 && videoHeight > 0 && cues.isNotEmpty())
    val playResX = (videoWidth.toDouble() * PLAY_RES_Y / videoHeight).roundToInt()
    require(playResX > 0)
    var previousEndUs = 0L
    cues.forEach { cue ->
        require(
            cue.startUs >= previousEndUs &&
                cue.endUs > cue.startUs &&
                cue.chinese.isNotBlank() &&
                cue.english.isNotBlank(),
        )
        requireSafeAssText(cue.chinese)
        requireSafeAssText(cue.english)
        previousEndUs = cue.endUs
    }

    return buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine("PlayResX: $playResX")
        appendLine("PlayResY: $PLAY_RES_Y")
        appendLine("WrapStyle: 2")
        appendLine("ScaledBorderAndShadow: yes")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine(STYLE_FORMAT)
        appendLine(CHINESE_STYLE)
        appendLine(ENGLISH_STYLE)
        appendLine()
        appendLine("[Events]")
        appendLine(EVENT_FORMAT)
        var previousEndCentiseconds = 0L
        cues.forEach { cue ->
            val startCentiseconds = maxOf(
                cue.startUs / MICROSECONDS_PER_CENTISECOND,
                previousEndCentiseconds,
            )
            val endCentiseconds = ceilCentiseconds(cue.endUs)
            require(endCentiseconds > startCentiseconds)
            val start = formatAssTime(startCentiseconds)
            val end = formatAssTime(endCentiseconds)
            appendLine("Dialogue: 0,$start,$end,Chinese,,0,0,0,,${cue.chinese}")
            appendLine("Dialogue: 0,$start,$end,English,,0,0,0,,${cue.english}")
            previousEndCentiseconds = endCentiseconds
        }
    }
}

private fun requireSafeAssText(text: String) {
    require(text.none { it == '\\' || it == '{' || it == '}' || it == '\r' || it == '\n' })
}

private fun ceilCentiseconds(timeUs: Long): Long {
    require(timeUs >= 0)
    return Math.addExact(timeUs, MICROSECONDS_PER_CENTISECOND - 1) /
        MICROSECONDS_PER_CENTISECOND
}

private fun formatAssTime(centiseconds: Long): String {
    require(centiseconds >= 0)
    val hours = centiseconds / CENTISECONDS_PER_HOUR
    val minutes = centiseconds / CENTISECONDS_PER_MINUTE % 60
    val seconds = centiseconds / CENTISECONDS_PER_SECOND % 60
    val remainder = centiseconds % CENTISECONDS_PER_SECOND
    return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, seconds, remainder)
}

private const val PLAY_RES_Y = 1_280
private const val MICROSECONDS_PER_CENTISECOND = 10_000L
private const val CENTISECONDS_PER_SECOND = 100L
private const val CENTISECONDS_PER_MINUTE = 6_000L
private const val CENTISECONDS_PER_HOUR = 360_000L
private const val STYLE_FORMAT =
    "Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding"
private const val CHINESE_STYLE =
    "Style: Chinese,Noto Sans CJK SC,42,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,-1,0,0,0,100,100,0,0,3,10.4,0,2,24,24,186,1"
private const val ENGLISH_STYLE =
    "Style: English,Noto Sans CJK SC,30,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,-1,0,0,0,100,100,0,0,3,10.4,0,2,24,24,128,1"
private const val EVENT_FORMAT = "Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text"
