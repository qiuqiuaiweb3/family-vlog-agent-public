package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.render.RenderClip
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class LocalAudioTranscriberTest {
    private val uri = mock(Uri::class.java)

    @Test
    fun `full generated vlog is decoded once and token times become source absolute times`() = runTest {
        val decoder = RecordingDecoder()
        val session = RecordingSession(
            accepted = listOf(SpeechSegmentSamples(4_000, FloatArray(8_000))),
            recognitions = listOf(
                SpeechRecognition(
                    language = "zh",
                    tokens = listOf("你", "好"),
                    timestampsSeconds = listOf(0f, 0.25f),
                ),
            ),
        )
        val source = source(hasAudio = true, durationUs = 1_000_000)
        val transcriber = LocalAudioTranscriber(decoder, SpeechSessionFactory { session })

        val cues = transcriber.transcribe(source)

        assertEquals(listOf(RenderClip(uri, 0, 1_000_000, true)), decoder.decoded)
        assertEquals(
            listOf(
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(
                        TimedToken(250_000, 500_000, "你"),
                        TimedToken(500_000, 750_000, "好"),
                    ),
                ),
            ),
            cues,
        )
        assertEquals(1, session.resetCalls)
        assertTrue(session.closed)
    }

    @Test
    fun `source without audio does not create a native session`() = runTest {
        var factoryCalls = 0
        val transcriber = LocalAudioTranscriber(
            decoder = RecordingDecoder(),
            sessionFactory = SpeechSessionFactory {
                factoryCalls += 1
                RecordingSession()
            },
        )

        assertTrue(transcriber.transcribe(source(hasAudio = false)).isEmpty())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `already cancelled silent call rethrows cancellation before creating session`() = runTest {
        var factoryCalls = 0
        val transcriber = LocalAudioTranscriber(
            decoder = RecordingDecoder(),
            sessionFactory = SpeechSessionFactory {
                factoryCalls += 1
                RecordingSession()
            },
        )
        val cancelledJob = Job().apply { cancel() }

        try {
            withContext(cancelledJob) { transcriber.transcribe(source(hasAudio = false)) }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, factoryCalls)
        }
    }

    @Test
    fun `audible source with no VAD segments returns no speech and closes session`() = runTest {
        val session = RecordingSession(accepted = emptyList(), flushed = emptyList())
        val transcriber = LocalAudioTranscriber(RecordingDecoder(), SpeechSessionFactory { session })

        assertTrue(transcriber.transcribe(source(hasAudio = true)).isEmpty())
        assertEquals(1, session.flushCalls)
        assertTrue(session.closed)
    }

    @Test
    fun `boundary-only and blank tokens validate their times then produce no speech`() = runTest {
        val session = RecordingSession(
            recognitions = listOf(
                SpeechRecognition(
                    language = "zh",
                    tokens = listOf("▁", " "),
                    timestampsSeconds = listOf(0f, 0.05f),
                ),
            ),
        )
        val transcriber = LocalAudioTranscriber(RecordingDecoder(), SpeechSessionFactory { session })

        assertTrue(transcriber.transcribe(source(hasAudio = true)).isEmpty())
        assertTrue(session.closed)
    }

    @Test
    fun `standalone sentencepiece boundaries are retained on following English words`() = runTest {
        val session = RecordingSession(
            accepted = listOf(SpeechSegmentSamples(0, FloatArray(4_000))),
            recognitions = listOf(
                SpeechRecognition(
                    language = "en",
                    tokens = listOf("▁", "hello", "▁", "world"),
                    timestampsSeconds = listOf(0f, 0.05f, 0.1f, 0.15f),
                ),
            ),
        )
        val transcriber = LocalAudioTranscriber(RecordingDecoder(), SpeechSessionFactory { session })

        val cues = transcriber.transcribe(source(hasAudio = true))

        assertEquals(
            listOf(
                TimedToken(0, 100_000, "▁hello"),
                TimedToken(100_000, 250_000, "▁world"),
            ),
            cues.single().tokens,
        )
    }

    @Test
    fun `tail VAD segment is flushed and English language is preserved`() = runTest {
        val session = RecordingSession(
            accepted = emptyList(),
            flushed = listOf(SpeechSegmentSamples(8_000, FloatArray(4_000))),
            recognitions = listOf(SpeechRecognition("en", listOf("hello"), listOf(0f))),
        )
        val transcriber = LocalAudioTranscriber(RecordingDecoder(), SpeechSessionFactory { session })

        assertEquals(
            listOf(
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(TimedToken(500_000, 750_000, "hello")),
                ),
            ),
            transcriber.transcribe(source(hasAudio = true)),
        )
        assertEquals(1, session.flushCalls)
    }

    @Test
    fun `Chinese English Chinese segments preserve language and source order`() = runTest {
        val session = RecordingSession(
            accepted = listOf(
                SpeechSegmentSamples(0, FloatArray(1_600)),
                SpeechSegmentSamples(3_200, FloatArray(1_600)),
                SpeechSegmentSamples(6_400, FloatArray(1_600)),
            ),
            recognitions = listOf(
                SpeechRecognition("<|zh|>", listOf("你好"), listOf(0f)),
                SpeechRecognition("<|en|>", listOf("▁hello"), listOf(0f)),
                SpeechRecognition("zh", listOf("再见"), listOf(0f)),
            ),
        )

        val cues = LocalAudioTranscriber(
            RecordingDecoder(),
            SpeechSessionFactory { session },
        ).transcribe(source(hasAudio = true))

        assertEquals(
            listOf(
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(TimedToken(0, 100_000, "你好")),
                ),
                TranscriptCue(
                    SubtitleLanguage.ENGLISH,
                    listOf(TimedToken(200_000, 300_000, "▁hello")),
                ),
                TranscriptCue(
                    SubtitleLanguage.CHINESE,
                    listOf(TimedToken(400_000, 500_000, "再见")),
                ),
            ),
            cues,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `token count mismatch and invalid language label fail closed`() = runTest {
        listOf(
            SpeechRecognition("zh", listOf("一"), emptyList()),
            SpeechRecognition("unsupported", listOf("a"), listOf(0f)),
            SpeechRecognition("<|zh", listOf("a"), listOf(0f)),
            SpeechRecognition("en|>", listOf("a"), listOf(0f)),
        ).forEach { recognition ->
            val session = RecordingSession(recognitions = listOf(recognition))
            val transcriber = LocalAudioTranscriber(RecordingDecoder(), SpeechSessionFactory { session })

            assertTranscriptionFailure { transcriber.transcribe(source(hasAudio = true)) }
            assertTrue(session.closed)
        }
    }

    @Test
    fun `overlapping or reversed vad segments fail during transcription`() = runTest {
        val session = RecordingSession(
            accepted = listOf(
                SpeechSegmentSamples(8_000, FloatArray(1_600)),
                SpeechSegmentSamples(0, FloatArray(1_600)),
            ),
            recognitions = listOf(
                SpeechRecognition("zh", listOf("后"), listOf(0f)),
                SpeechRecognition("zh", listOf("前"), listOf(0f)),
            ),
        )

        assertTranscriptionFailure {
            LocalAudioTranscriber(
                RecordingDecoder(),
                SpeechSessionFactory { session },
            ).transcribe(source(hasAudio = true))
        }
        assertTrue(session.closed)
    }

    @Test
    fun `decoder and native linkage failures are sanitized and close acquired session`() = runTest {
        val decoderSession = RecordingSession()
        assertTranscriptionFailure {
            LocalAudioTranscriber(
                AudioIntervalDecoder { _, _ -> throw IllegalStateException("sensitive") },
                SpeechSessionFactory { decoderSession },
            ).transcribe(source(hasAudio = true))
        }
        assertTrue(decoderSession.closed)

        val linkageSession = RecordingSession(linkageFailure = UnsatisfiedLinkError("sensitive"))
        assertTranscriptionFailure {
            LocalAudioTranscriber(
                RecordingDecoder(),
                SpeechSessionFactory { linkageSession },
            ).transcribe(source(hasAudio = true))
        }
        assertTrue(linkageSession.closed)

        assertTranscriptionFailure {
            LocalAudioTranscriber(
                RecordingDecoder(),
                SpeechSessionFactory { throw UnsatisfiedLinkError("sensitive") },
            ).transcribe(source(hasAudio = true))
        }
    }

    @Test
    fun `cancellation is rethrown after close while close failure overrides cancellation`() = runTest {
        val cancellation = CancellationException("cancel")
        val cleanSession = RecordingSession(recognitionFailure = cancellation)
        val cleanTranscriber = LocalAudioTranscriber(
            RecordingDecoder(),
            SpeechSessionFactory { cleanSession },
        )
        try {
            cleanTranscriber.transcribe(source(hasAudio = true))
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
            assertTrue(cleanSession.closed)
        }

        val failedClose = RecordingSession(
            recognitionFailure = CancellationException("cancel"),
            closeFailure = IllegalStateException("close"),
        )
        assertTranscriptionFailure {
            LocalAudioTranscriber(
                RecordingDecoder(),
                SpeechSessionFactory { failedClose },
            ).transcribe(source(hasAudio = true))
        }
        assertTrue(failedClose.closed)
    }

    private suspend fun assertTranscriptionFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected transcription failure")
        } catch (failure: SubtitleException) {
            assertEquals(SubtitlePhase.TRANSCRIBING, failure.phase)
            assertEquals(SubtitleFailureCode.TRANSCRIPTION_FAILED, failure.code)
        }
    }

    private fun source(hasAudio: Boolean, durationUs: Long = 1_000_000) = SubtitleSource(
        uri = uri,
        durationUs = durationUs,
        videoWidth = 720,
        videoHeight = 1_280,
        hasAudio = hasAudio,
    )

    private class RecordingDecoder : AudioIntervalDecoder {
        val decoded = mutableListOf<RenderClip>()

        override suspend fun decode(clip: RenderClip, consume: suspend (FloatArray) -> Unit) {
            decoded += clip
            consume(FloatArray(512))
        }
    }

    private class RecordingSession(
        private val accepted: List<SpeechSegmentSamples> = listOf(
            SpeechSegmentSamples(startSample = 0, samples = FloatArray(1_600)),
        ),
        private val flushed: List<SpeechSegmentSamples> = emptyList(),
        private val recognitions: List<SpeechRecognition> = listOf(
            SpeechRecognition("zh", listOf("字"), listOf(0f)),
        ),
        private val recognitionFailure: CancellationException? = null,
        private val linkageFailure: LinkageError? = null,
        private val closeFailure: RuntimeException? = null,
    ) : SpeechSession {
        var resetCalls = 0
        var flushCalls = 0
        var closed = false
        private var recognitionIndex = 0

        override fun reset() {
            resetCalls += 1
        }

        override fun accept(samples: FloatArray): List<SpeechSegmentSamples> = accepted

        override fun flush(): List<SpeechSegmentSamples> {
            flushCalls += 1
            return flushed
        }

        override fun recognize(samples: FloatArray): SpeechRecognition {
            recognitionFailure?.let { throw it }
            linkageFailure?.let { throw it }
            return recognitions.getOrElse(recognitionIndex++) { recognitions.last() }
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
