package com.chill.familyvlog.ai

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.analysis.AndroidSourceMetadataReader
import com.chill.familyvlog.input.FixtureMediaProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceBytesReaderAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun portraitFixture_readsExactOriginalBytesThroughFileDescriptor() = runBlocking {
        val fixture = FixtureMediaProvider.PORTRAIT_AUDIO
        val expected = requireNotNull(
            context.contentResolver.openInputStream(FixtureMediaProvider.uriFor(fixture)),
        ).use { it.readBytes() }

        val result = AndroidSourceBytesReader(context.contentResolver).read(
            wholeFileUri(fixture),
            expected.size,
        )

        assertArrayEquals(expected, (result as SourceBytesReadResult.Fits).bytes)
    }

    @Test
    fun metadataFixture_remainsByteExactBeforeAndAfterMetadataRead() = runBlocking {
        val fixture = FixtureMediaProvider.SOURCE_METADATA_UNKNOWN_TRACK
        val uri = FixtureMediaProvider.uriFor(fixture)
        val before = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

        AndroidSourceMetadataReader(context).read(uri)

        val after = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val inline = AndroidSourceBytesReader(context.contentResolver).read(wholeFileUri(fixture), before.size)
        assertArrayEquals(before, after)
        assertArrayEquals(before, (inline as SourceBytesReadResult.Fits).bytes)
    }

    @Test
    fun knownLengthAtLimit_readsExactOriginalBytes() = runBlocking {
        val fixture = FixtureMediaProvider.PORTRAIT_AUDIO
        val expected = requireNotNull(
            context.contentResolver.openInputStream(FixtureMediaProvider.uriFor(fixture)),
        ).use { it.readBytes() }
        val source = File.createTempFile("bounded-source-", ".bin", targetContext.cacheDir)
        try {
            source.writeBytes(expected)
            val result = AndroidSourceBytesReader(context.contentResolver).read(
                Uri.fromFile(source),
                expected.size,
            )

            assertArrayEquals(expected, (result as SourceBytesReadResult.Fits).bytes)
        } finally {
            source.delete()
        }
    }

    @Test
    fun unknownLength_readsAtMostOneBytePastLimitBeforeRejecting() = runBlocking {
        val fixture = FixtureMediaProvider.PORTRAIT_AUDIO
        val actualSize = requireNotNull(
            context.contentResolver.openInputStream(FixtureMediaProvider.uriFor(fixture)),
        ).use { it.readBytes().size }

        val result = AndroidSourceBytesReader(context.contentResolver).read(
            wholeFileUri(fixture),
            actualSize - 1,
        )

        assertEquals(SourceBytesReadResult.TooLarge, result)
    }

    @Test
    fun knownLength_rejectsBeforeOpeningAnInputStream() = runBlocking {
        val fixture = FixtureMediaProvider.PORTRAIT_AUDIO
        val bytes = requireNotNull(
            context.contentResolver.openInputStream(FixtureMediaProvider.uriFor(fixture)),
        ).use { it.readBytes() }
        val source = File.createTempFile("bounded-source-", ".bin", targetContext.cacheDir)
        try {
            source.writeBytes(bytes)
            val result = AndroidSourceBytesReader(context.contentResolver).read(
                Uri.fromFile(source),
                bytes.size - 1,
            )

            assertEquals(SourceBytesReadResult.TooLarge, result)
        } finally {
            source.delete()
        }
    }

    @Test
    fun fixtureFileBoundary_rejectsUnknownPathsAndEveryWriteMode() {
        val known = wholeFileUri(FixtureMediaProvider.PORTRAIT_AUDIO)
        val unknownPath = Uri.parse("content://${FixtureMediaProvider.AUTHORITY}/unknown?view=whole-file")
        val unknownView = FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO)
            .buildUpon()
            .appendQueryParameter("view", "converted")
            .build()
        val repeatedView = known.buildUpon()
            .appendQueryParameter("view", "whole-file")
            .build()

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(unknownPath, "r")
        }
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(unknownView, "r")
        }
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(repeatedView, "r")
        }
        listOf("w", "wa", "rw", "rwt").forEach { mode ->
            assertThrows(FileNotFoundException::class.java) {
                context.contentResolver.openFileDescriptor(known, mode)
            }
        }
    }

    private fun wholeFileUri(name: String): Uri = FixtureMediaProvider.uriFor(name)
        .buildUpon()
        .appendQueryParameter("view", "whole-file")
        .build()
}
