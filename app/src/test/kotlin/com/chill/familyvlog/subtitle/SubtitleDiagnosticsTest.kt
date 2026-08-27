package com.chill.familyvlog.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDiagnosticsTest {
    @Test
    fun `language normalization emits only fixed safe labels`() {
        assertEquals("zh", normalizeDiagnosticLanguage(" <|ZH|> "))
        assertEquals("yue", normalizeDiagnosticLanguage("YUE"))
        assertEquals("unknown", normalizeDiagnosticLanguage("  "))
        assertEquals("other", normalizeDiagnosticLanguage("private spoken words"))
    }

    @Test
    fun `diagnostic contains fixed rule and omits exception messages`() {
        val sensitiveMessage = "private-media-name-and-recognized-text"
        val diagnostic = subtitleDiagnostic(
            operation = SubtitleDiagnosticOperation.TOKEN_VALIDATION,
            failure = IllegalArgumentException(sensitiveMessage),
            language = "unexpected private language label",
            tokenCount = 2,
            timestampCount = 1,
            timestampRule = SubtitleTimestampRule.TOKEN_COUNT_MATCH,
        )

        assertTrue(diagnostic.contains("phase=TRANSCRIBING"))
        assertTrue(diagnostic.contains("code=TRANSCRIPTION_FAILED"))
        assertTrue(diagnostic.contains("operation=recognition_tokens"))
        assertTrue(diagnostic.contains("exception_type=IllegalArgumentException"))
        assertTrue(diagnostic.contains("cause_type=none"))
        assertTrue(diagnostic.contains("lang=other"))
        assertTrue(diagnostic.contains("token_count=2"))
        assertTrue(diagnostic.contains("timestamp_count=1"))
        assertTrue(diagnostic.contains("timestamp_rule=token_count_match"))
        assertFalse(diagnostic.contains(sensitiveMessage))
        assertFalse(diagnostic.contains("unexpected private language label"))
    }
}
