package com.chill.familyvlog.analysis

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSourceMetadataReaderAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun platformReader_exposesOnlyAllowlistedTechnicalMetadataKeys() = runBlocking {
        val result = AndroidSourceMetadataReader(context).read(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.LANDSCAPE_SILENT),
        )

        assertTrue(result.metadata.keys.all(ALLOWED_KEYS::contains))
    }

    @Test
    fun platformFixture_readsDateLocationTitleAndXmpWhileDiagnosingUnknownTrack() = runBlocking {
        val result = AndroidSourceMetadataReader(context).read(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.SOURCE_METADATA_UNKNOWN_TRACK),
        )

        assertEquals("20260819T012030.000Z", (result.metadata["date"] as JsonPrimitive).content)
        assertEquals("+35.0000+139.0000/", (result.metadata["location"] as JsonPrimitive).content)
        assertEquals("Synthetic metadata fixture", (result.metadata["title"] as JsonPrimitive).content)
        assertTrue((result.metadata["xmp"] as JsonPrimitive).content.contains("Synthetic XMP value"))
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.all { it.code == "source_metadata_unknown_track" })
    }

    @Test
    fun injectedSession_doesNotInferUriAndAlwaysCloses() = runBlocking {
        val uri = Uri.parse("content://metadata-test/private-name.mp4")
        val session = FakeSession(
            values = mapOf(SourceMetadataField.TITLE to "Synthetic title"),
        )
        val reader = AndroidSourceMetadataReader(
            sessionFactory = SourceMetadataSessionFactory { actualUri ->
                assertSame(uri, actualUri)
                session
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val result = reader.read(uri)

        assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Synthetic title"))), result.metadata)
        assertFalse(result.metadata.toString().contains("private-name.mp4"))
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun injectedSession_unknownTrackIsDiagnosticWithoutHidingReadableMetadata() = runBlocking {
        val session = FakeSession(
            values = mapOf(SourceMetadataField.DATE to "2026-08-19T10:20:30+09:00"),
            trackMimeTypes = listOf("video/avc", "application/x-subrip"),
        )
        val reader = AndroidSourceMetadataReader(
            sessionFactory = SourceMetadataSessionFactory { session },
            dispatcher = Dispatchers.Unconfined,
        )

        val result = reader.read(Uri.EMPTY)

        assertEquals(
            JsonObject(mapOf("date" to JsonPrimitive("2026-08-19T10:20:30+09:00"))),
            result.metadata,
        )
        assertEquals("source_metadata_unknown_track", result.diagnostics.single().code)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun ordinarySessionOpenFailureReturnsNonBlockingDiagnostic() = runBlocking {
        val reader = AndroidSourceMetadataReader(
            sessionFactory = SourceMetadataSessionFactory { throw IllegalStateException("unreadable") },
            dispatcher = Dispatchers.Unconfined,
        )

        val result = reader.read(Uri.EMPTY)

        assertEquals(JsonObject(emptyMap()), result.metadata)
        assertEquals("source_metadata_read_failed", result.diagnostics.single().code)
    }

    @Test
    fun descriptorRange_readsRelativeXmpBytesFromTheSameDescriptorSlice() {
        val file = File(targetContext.cacheDir, "source-metadata-range-${System.nanoTime()}.bin")
        val outerPrefix = byteArrayOf(1, 2, 3, 4)
        val innerPrefix = byteArrayOf(5, 6)
        val xmp = "<x:xmpmeta>中文 English</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val innerSuffix = byteArrayOf(7, 8, 9)
        file.writeBytes(outerPrefix + innerPrefix + xmp + innerSuffix)

        try {
            val parcel = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(
                parcel,
                outerPrefix.size.toLong(),
                (innerPrefix.size + xmp.size + innerSuffix.size).toLong(),
            ).use { descriptor ->
                assertArrayEquals(
                    xmp,
                    readDescriptorRange(
                        descriptor = descriptor,
                        relativeOffset = innerPrefix.size.toLong(),
                        length = xmp.size.toLong(),
                    ),
                )
                assertEquals(
                    null,
                    readDescriptorRange(
                        descriptor = descriptor,
                        relativeOffset = innerPrefix.size.toLong(),
                        length = (xmp.size + innerSuffix.size + 1).toLong(),
                    ),
                )
                assertEquals(
                    null,
                    readDescriptorRange(
                        descriptor = descriptor,
                        relativeOffset = 0L,
                        length = MAX_READABLE_SOURCE_XMP_BYTES + 1L,
                    ),
                )
            }
        } finally {
            assertTrue(file.delete() || !file.exists())
        }
    }

    @Test
    fun sessionCancellation_isPropagatedAfterClosing() = runBlocking {
        val cancellation = CancellationException("metadata")
        val session = FakeSession(readFailure = cancellation)
        val reader = AndroidSourceMetadataReader(
            sessionFactory = SourceMetadataSessionFactory { session },
            dispatcher = Dispatchers.Unconfined,
        )

        val failure = runCatching { reader.read(Uri.EMPTY) }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(1, session.closeCalls)
    }

    private class FakeSession(
        private val values: Map<SourceMetadataField, String?> = emptyMap(),
        private val readFailure: Throwable? = null,
        private val trackMimeTypes: List<String> = emptyList(),
    ) : SourceMetadataSession {
        var closeCalls = 0

        override fun read(field: SourceMetadataField): String? {
            readFailure?.let { throw it }
            return values[field]
        }

        override fun readXmpBytes(): ByteArray? = null

        override fun readTrackMimeTypes(): List<String> = trackMimeTypes

        override fun close() {
            closeCalls += 1
        }
    }

    private companion object {
        val ALLOWED_KEYS = setOf(
            "date",
            "location",
            "author",
            "writer",
            "title",
            "artist",
            "album",
            "albumartist",
            "composer",
            "genre",
            "year",
            "xmp",
        )
    }
}
