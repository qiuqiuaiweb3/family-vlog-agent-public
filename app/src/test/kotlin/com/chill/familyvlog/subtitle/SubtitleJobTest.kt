package com.chill.familyvlog.subtitle

import android.net.Uri
import com.chill.familyvlog.output.PublicationReceipt
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SubtitleJobTest {
    @Test
    fun `generic invalid language after valid speech stops before translation for every token shape`() = runTest {
        val sourceUri = mock(Uri::class.java)
        val validRecognition = SpeechRecognition(
            language = "zh",
            tokens = listOf("text"),
            timestampsSeconds = listOf(0f),
        )
        listOf(
            SpeechRecognition(
                language = "unsupported",
                tokens = listOf("▁", " "),
                timestampsSeconds = listOf(0f, 0.025f),
            ),
            SpeechRecognition(
                language = "unsupported",
                tokens = emptyList(),
                timestampsSeconds = emptyList(),
            ),
            SpeechRecognition(
                language = "<|zh",
                tokens = listOf("a"),
                timestampsSeconds = listOf(0f),
            ),
            SpeechRecognition(
                language = "en|>",
                tokens = listOf("a"),
                timestampsSeconds = listOf(0f),
            ),
        ).forEach { invalidRecognition ->
            var translatorFactoryCalls = 0
            var downstreamCalls = 0
            var recognitionIndex = 0
            val recognitions = listOf(validRecognition, invalidRecognition)
            val invalidLanguageSession = object : SpeechSession {
                override fun reset() = Unit

                override fun accept(samples: FloatArray): List<SpeechSegmentSamples> =
                    listOf(
                        SpeechSegmentSamples(0, FloatArray(800)),
                        SpeechSegmentSamples(800, FloatArray(800)),
                    )

                override fun flush(): List<SpeechSegmentSamples> = emptyList()

                override fun recognize(samples: FloatArray) = recognitions[recognitionIndex++]

                override fun close() = Unit
            }
            val failure = runCatching {
                SubtitleJob(
                    inspector = SubtitleSourceInspector {
                        SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
                    },
                    transcriber = LocalAudioTranscriber(
                        decoder = AudioIntervalDecoder { _, consume -> consume(FloatArray(1_600)) },
                        sessionFactory = SpeechSessionFactory { invalidLanguageSession },
                    ),
                    translatorFactory = CaptionTranslatorFactory {
                        translatorFactoryCalls += 1
                        throw AssertionError("translator must not be created")
                    },
                    planner = BilingualCaptionPlanner { _, _, _, _ ->
                        downstreamCalls += 1
                        emptyList()
                    },
                    assBuilder = AssSubtitleBuilder { _, _, _ ->
                        downstreamCalls += 1
                        "ass"
                    },
                    exporter = SubtitleExporter { _, _, _, _ ->
                        downstreamCalls += 1
                        throw AssertionError("export must not run")
                    },
                ).run(sourceUri, {}, {})
            }.exceptionOrNull()

            assertTrue(failure is SubtitleException)
            failure as SubtitleException
            assertEquals(SubtitlePhase.TRANSCRIBING, failure.phase)
            assertEquals(SubtitleFailureCode.TRANSCRIPTION_FAILED, failure.code)
            assertEquals(0, translatorFactoryCalls)
            assertEquals(0, downstreamCalls)
        }
    }

    @Test
    fun `source without audio stops before transcription translation ass and export`() = runTest {
        val sourceUri = mock(Uri::class.java)
        val calls = mutableListOf<String>()
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                calls += "inspect"
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = false)
            },
            transcriber = AudioTranscriber {
                calls += "transcribe"
                emptyList()
            },
            translatorFactory = CaptionTranslatorFactory {
                calls += "translator"
                throw AssertionError("translator must not be created")
            },
            planner = BilingualCaptionPlanner { _, _, _, _ ->
                calls += "plan"
                emptyList()
            },
            assBuilder = AssSubtitleBuilder { _, _, _ ->
                calls += "ass"
                ""
            },
            exporter = SubtitleExporter { _, _, _, _ ->
                calls += "export"
                throw AssertionError("export must not run")
            },
        )

        val result = job.run(sourceUri, {}, {})

        assertSame(SubtitleJobResult.NoSpeech, result)
        assertEquals(listOf("inspect"), calls)
    }

    @Test
    fun `audible source with empty transcript stops before model ass and export`() = runTest {
        val sourceUri = mock(Uri::class.java)
        val calls = mutableListOf<String>()
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                calls += "inspect"
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
            },
            transcriber = AudioTranscriber { calls += "transcribe"; emptyList() },
            translatorFactory = CaptionTranslatorFactory {
                calls += "translator"
                throw AssertionError("translator must not be created")
            },
            planner = BilingualCaptionPlanner { _, _, _, _ ->
                calls += "plan"
                throw AssertionError("planner must not run")
            },
            assBuilder = AssSubtitleBuilder { _, _, _ ->
                calls += "ass"
                throw AssertionError("ASS must not be built")
            },
            exporter = SubtitleExporter { _, _, _, _ ->
                calls += "export"
                throw AssertionError("export must not run")
            },
        )

        val result = job.run(sourceUri, {}, {})

        assertSame(SubtitleJobResult.NoSpeech, result)
        assertEquals(listOf("inspect", "transcribe"), calls)
    }

    @Test
    fun `model preparation failure has exact phase closes translator and stops downstream`() = runTest {
        val sourceUri = mock(Uri::class.java)
        var closed = false
        var downstreamCalls = 0
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) {
                onDownloadRequired()
                throw IllegalStateException("sensitive")
            }

            override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String =
                throw AssertionError("translate must not run")

            override fun close() {
                closed = true
            }
        }
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
            },
            transcriber = AudioTranscriber {
                listOf(
                    TranscriptCue(
                        SubtitleLanguage.CHINESE,
                        listOf(TimedToken(0, 1_000_000, "中文")),
                    ),
                )
            },
            translatorFactory = CaptionTranslatorFactory { translator },
            planner = BilingualCaptionPlanner { _, _, _, _ -> downstreamCalls += 1; emptyList() },
            assBuilder = AssSubtitleBuilder { _, _, _ -> downstreamCalls += 1; "ass" },
            exporter = SubtitleExporter { _, _, _, _ ->
                downstreamCalls += 1
                throw AssertionError("export must not run")
            },
        )
        val phases = mutableListOf<SubtitlePhase>()

        val failure = runCatching { job.run(sourceUri, phases::add, {}) }.exceptionOrNull()

        assertTrue(failure is SubtitleException)
        failure as SubtitleException
        assertEquals(SubtitlePhase.DOWNLOADING_MODEL, failure.phase)
        assertEquals(SubtitleFailureCode.MODEL_DOWNLOAD_FAILED, failure.code)
        assertEquals(listOf(SubtitlePhase.TRANSCRIBING, SubtitlePhase.DOWNLOADING_MODEL), phases)
        assertEquals(0, downstreamCalls)
        assertTrue(closed)
    }

    @Test
    fun `ready model skips download phase and ass failure is classified as layout`() = runTest {
        val sourceUri = mock(Uri::class.java)
        var closed = false
        var exportCalls = 0
        val transcript = listOf(
            TranscriptCue(
                SubtitleLanguage.ENGLISH,
                listOf(TimedToken(0, 1_000_000, "hello")),
            ),
        )
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) = Unit
            override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String) = "你好"
            override fun close() { closed = true }
        }
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
            },
            transcriber = AudioTranscriber { transcript },
            translatorFactory = CaptionTranslatorFactory { translator },
            planner = BilingualCaptionPlanner { _, _, _, _ ->
                listOf(BilingualCaptionCue(0, 1_000_000, "你好", "hello"))
            },
            assBuilder = AssSubtitleBuilder { _, _, _ -> throw IllegalArgumentException("layout") },
            exporter = SubtitleExporter { _, _, _, _ ->
                exportCalls += 1
                throw AssertionError("export must not run")
            },
        )
        val phases = mutableListOf<SubtitlePhase>()

        val failure = runCatching { job.run(sourceUri, phases::add, {}) }.exceptionOrNull()

        assertTrue(failure is SubtitleException)
        failure as SubtitleException
        assertEquals(SubtitlePhase.TRANSLATING, failure.phase)
        assertEquals(SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED, failure.code)
        assertEquals(listOf(SubtitlePhase.TRANSCRIBING, SubtitlePhase.TRANSLATING), phases)
        assertEquals(0, exportCalls)
        assertTrue(closed)
    }

    @Test
    fun `translation failure closes translator and stops before ass and export`() = runTest {
        val sourceUri = mock(Uri::class.java)
        var closed = false
        var downstreamCalls = 0
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) = Unit
            override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String =
                throw IllegalStateException("sensitive")
            override fun close() { closed = true }
        }
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
            },
            transcriber = AudioTranscriber {
                listOf(TranscriptCue(SubtitleLanguage.CHINESE, listOf(TimedToken(0, 100, "中文"))))
            },
            translatorFactory = CaptionTranslatorFactory { translator },
            planner = BilingualCaptionPlanner { _, session, _, _ ->
                session.translate(SubtitleLanguage.CHINESE, "中文")
                emptyList()
            },
            assBuilder = AssSubtitleBuilder { _, _, _ -> downstreamCalls += 1; "ass" },
            exporter = SubtitleExporter { _, _, _, _ ->
                downstreamCalls += 1
                throw AssertionError("export must not run")
            },
        )

        val failure = runCatching { job.run(sourceUri, {}, {}) }.exceptionOrNull()

        assertTrue(failure is SubtitleException)
        failure as SubtitleException
        assertEquals(SubtitlePhase.TRANSLATING, failure.phase)
        assertEquals(SubtitleFailureCode.TRANSLATION_FAILED, failure.code)
        assertTrue(closed)
        assertEquals(0, downstreamCalls)
    }

    @Test
    fun `translation cancellation closes translator and remains cancellation`() = runTest {
        val sourceUri = mock(Uri::class.java)
        val cancellation = CancellationException("cancel")
        var closed = false
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) = Unit
            override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String =
                throw cancellation
            override fun close() { closed = true }
        }
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector {
                SubtitleSource(sourceUri, 1_000_000, 720, 1_280, hasAudio = true)
            },
            transcriber = AudioTranscriber {
                listOf(TranscriptCue(SubtitleLanguage.ENGLISH, listOf(TimedToken(0, 100, "hello"))))
            },
            translatorFactory = CaptionTranslatorFactory { translator },
            planner = LocalBilingualCaptionPlanner(SingleLineTextMeasurer { _, _ -> 1f }),
            assBuilder = AssSubtitleBuilder { _, _, _ -> throw AssertionError("ASS must not run") },
            exporter = SubtitleExporter { _, _, _, _ -> throw AssertionError("export must not run") },
        )

        val failure = runCatching { job.run(sourceUri, {}, {}) }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertTrue(closed)
    }

    @Test
    fun `speech path preserves strict order and publishes a separate result`() = runTest {
        val sourceUri = mock(Uri::class.java)
        val publishedUri = mock(Uri::class.java)
        val receipt = PublicationReceipt(publishedUri)
        val calls = mutableListOf<String>()
        val translationCalls = mutableListOf<Pair<SubtitleLanguage, String>>()
        val source = SubtitleSource(sourceUri, 2_000_000, 720, 1_280, hasAudio = true)
        val transcript = listOf(
            TranscriptCue(
                language = SubtitleLanguage.CHINESE,
                tokens = listOf(TimedToken(0, 500_000, "中文")),
            ),
            TranscriptCue(
                language = SubtitleLanguage.ENGLISH,
                tokens = listOf(TimedToken(500_000, 1_000_000, "English")),
            ),
            TranscriptCue(
                language = SubtitleLanguage.CHINESE,
                tokens = listOf(TimedToken(1_000_000, 1_500_000, "再见")),
            ),
        )
        val captions = listOf(
            BilingualCaptionCue(0, 500_000, "中文", "Chinese"),
            BilingualCaptionCue(500_000, 1_000_000, "英文", "English"),
            BilingualCaptionCue(1_000_000, 1_500_000, "再见", "Goodbye"),
        )
        val translator = object : CaptionTranslatorSession {
            override suspend fun prepare(onDownloadRequired: () -> Unit) {
                calls += "prepare"
                onDownloadRequired()
            }

            override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String {
                calls += "translate"
                translationCalls += sourceLanguage to text
                return "translated"
            }

            override fun close() {
                calls += "close"
            }
        }
        val phases = mutableListOf<SubtitlePhase>()
        val job = SubtitleJob(
            inspector = SubtitleSourceInspector { calls += "inspect"; source },
            transcriber = AudioTranscriber { calls += "transcribe"; transcript },
            translatorFactory = CaptionTranslatorFactory { calls += "translator"; translator },
            planner = BilingualCaptionPlanner { actual, session, _, _ ->
                calls += "plan"
                assertEquals(transcript, actual)
                actual.forEach { cue ->
                    session.translate(cue.language, cue.tokens.single().text)
                }
                captions
            },
            assBuilder = AssSubtitleBuilder { _, _, actual ->
                calls += "ass"
                assertEquals(captions, actual)
                "ass"
            },
            exporter = SubtitleExporter { actualSource, ass, onSaving, onCommitted ->
                calls += "export"
                assertEquals(source, actualSource)
                assertEquals("ass", ass)
                onSaving()
                onCommitted(receipt)
                receipt
            },
        )

        val result = job.run(sourceUri, phases::add) { calls += "committed" }

        assertEquals(SubtitleJobResult.Published(receipt), result)
        assertEquals(
            listOf(
                "inspect", "transcribe", "translator", "prepare", "plan",
                "translate", "translate", "translate", "close",
                "ass", "export", "committed",
            ),
            calls,
        )
        assertEquals(
            listOf(
                SubtitleLanguage.CHINESE to "中文",
                SubtitleLanguage.ENGLISH to "English",
                SubtitleLanguage.CHINESE to "再见",
            ),
            translationCalls,
        )
        assertEquals(
            listOf(
                SubtitlePhase.TRANSCRIBING,
                SubtitlePhase.DOWNLOADING_MODEL,
                SubtitlePhase.TRANSLATING,
                SubtitlePhase.RENDERING,
                SubtitlePhase.SAVING,
            ),
            phases,
        )
    }
}
