package com.chill.familyvlog.output

import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputInspectorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test
    fun realFixtures_reportOnlyParseabilityAndTrackPresence() = runBlocking {
        val silent = copyFixture(FixtureMediaProvider.LANDSCAPE_SILENT, "silent")
        val audio = copyFixture(FixtureMediaProvider.PORTRAIT_AUDIO, "audio")
        try {
            val inspector = MediaExtractorOutputInspector()
            val silentResult = inspector.inspect(silent)
            val audioResult = inspector.inspect(audio)

            assertTrue(silentResult.nonEmpty)
            assertTrue(silentResult.parseable)
            assertTrue(silentResult.hasVideo)
            assertFalse(silentResult.hasAudio)
            assertTrue(audioResult.nonEmpty)
            assertTrue(audioResult.parseable)
            assertTrue(audioResult.hasVideo)
            assertTrue(audioResult.hasAudio)
        } finally {
            silent.delete()
            audio.delete()
        }
    }

    @Test
    fun emptyAndGarbageFiles_failTheTechnicalInspectionGates() = runBlocking {
        val empty = outputFile("empty").apply { createNewFile() }
        val garbage = outputFile("garbage").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val inspector = MediaExtractorOutputInspector()
            val emptyResult = inspector.inspect(empty)
            val garbageResult = inspector.inspect(garbage)

            assertFalse(emptyResult.nonEmpty)
            assertFalse(emptyResult.parseable)
            assertTrue(garbageResult.nonEmpty)
            assertFalse(garbageResult.parseable)
            assertFalse(garbageResult.hasVideo)
            assertFalse(garbageResult.hasAudio)
        } finally {
            empty.delete()
            garbage.delete()
        }
    }

    private fun copyFixture(name: String, label: String): File {
        val output = outputFile(label)
        instrumentation.context.contentResolver
            .openInputStream(FixtureMediaProvider.uriFor(name))
            .use { input ->
                output.outputStream().use { target -> requireNotNull(input).copyTo(target) }
            }
        return output
    }

    private fun outputFile(label: String) =
        File(targetContext.cacheDir, "output-inspector-$label-${UUID.randomUUID()}.mp4")
}
