package com.chill.familyvlog.analysis

import android.media.MediaMetadataRetriever
import com.chill.familyvlog.contract.Diagnostic
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class SourceMetadataReaderTest {
    @Test
    fun `each source field maps to its exact Android 36 metadata key`() {
        assertEquals(MediaMetadataRetriever.METADATA_KEY_DATE, SourceMetadataField.DATE.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_LOCATION, SourceMetadataField.LOCATION.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_AUTHOR, SourceMetadataField.AUTHOR.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_WRITER, SourceMetadataField.WRITER.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_TITLE, SourceMetadataField.TITLE.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_ARTIST, SourceMetadataField.ARTIST.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_ALBUM, SourceMetadataField.ALBUM.androidMetadataKey())
        assertEquals(
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            SourceMetadataField.ALBUMARTIST.androidMetadataKey(),
        )
        assertEquals(MediaMetadataRetriever.METADATA_KEY_COMPOSER, SourceMetadataField.COMPOSER.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_GENRE, SourceMetadataField.GENRE.androidMetadataKey())
        assertEquals(MediaMetadataRetriever.METADATA_KEY_YEAR, SourceMetadataField.YEAR.androidMetadataKey())
    }

    @Test
    fun `all platform values and strict UTF-8 XMP keep their original semantics`() {
        val values = SourceMetadataField.entries.associateWith { field ->
            when (field) {
                SourceMetadataField.DATE -> "2026-08-19T10:20:30+09:00"
                SourceMetadataField.LOCATION -> "+35.0000+139.0000/"
                SourceMetadataField.AUTHOR -> "  作者 Author  "
                SourceMetadataField.WRITER -> "Writer"
                SourceMetadataField.TITLE -> "家庭 Vlog"
                SourceMetadataField.ARTIST -> "Artist"
                SourceMetadataField.ALBUM -> "Album"
                SourceMetadataField.ALBUMARTIST -> "Album Artist"
                SourceMetadataField.COMPOSER -> "Composer"
                SourceMetadataField.GENRE -> "Genre"
                SourceMetadataField.YEAR -> "2026"
            }
        }
        val xmp = "  <x:xmpmeta>中文 English</x:xmpmeta>\n"

        val result = collectSourceMetadata(
            FakeMetadataAccess(values = values, xmp = xmp.toByteArray(Charsets.UTF_8)),
        )

        assertEquals(
            JsonObject(
                linkedMapOf(
                    "date" to JsonPrimitive(values.getValue(SourceMetadataField.DATE)),
                    "location" to JsonPrimitive(values.getValue(SourceMetadataField.LOCATION)),
                    "author" to JsonPrimitive(values.getValue(SourceMetadataField.AUTHOR)),
                    "writer" to JsonPrimitive(values.getValue(SourceMetadataField.WRITER)),
                    "title" to JsonPrimitive(values.getValue(SourceMetadataField.TITLE)),
                    "artist" to JsonPrimitive(values.getValue(SourceMetadataField.ARTIST)),
                    "album" to JsonPrimitive(values.getValue(SourceMetadataField.ALBUM)),
                    "albumartist" to JsonPrimitive(values.getValue(SourceMetadataField.ALBUMARTIST)),
                    "composer" to JsonPrimitive(values.getValue(SourceMetadataField.COMPOSER)),
                    "genre" to JsonPrimitive(values.getValue(SourceMetadataField.GENRE)),
                    "year" to JsonPrimitive(values.getValue(SourceMetadataField.YEAR)),
                    "xmp" to JsonPrimitive(xmp),
                ),
            ),
            result.metadata,
        )
        assertEquals(emptyList<Diagnostic>(), result.diagnostics)
    }

    @Test
    fun `missing and unreadable fields are omitted without hiding readable fields`() {
        val access = object : SourceMetadataAccess {
            override fun read(field: SourceMetadataField): String? = when (field) {
                SourceMetadataField.AUTHOR -> throw IllegalStateException("unreadable")
                SourceMetadataField.TITLE -> "Readable title"
                else -> null
            }

            override fun readXmpBytes(): ByteArray = throw IllegalArgumentException("bad range")
        }

        val result = collectSourceMetadata(access)

        assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Readable title"))), result.metadata)
        assertFalse(result.metadata.containsKey("uri"))
        assertFalse(result.metadata.containsKey("filename"))
        assertEquals(
            listOf(
                Diagnostic("source_metadata_field_unreadable", "Could not read source metadata field: author"),
                Diagnostic("source_metadata_xmp_unreadable", "Could not read source XMP metadata"),
            ),
            result.diagnostics,
        )
    }

    @Test
    fun `malformed UTF-8 XMP is omitted instead of replacing bytes`() {
        val result = collectSourceMetadata(
            FakeMetadataAccess(xmp = byteArrayOf(0xc3.toByte(), 0x28)),
        )

        assertEquals(JsonObject(emptyMap()), result.metadata)
        assertEquals(
            listOf(Diagnostic("source_metadata_xmp_unreadable", "Could not decode source XMP metadata as UTF-8")),
            result.diagnostics,
        )
    }

    @Test
    fun `unknown non audiovisual tracks are diagnostic only`() {
        val result = collectSourceMetadata(
            FakeMetadataAccess(
                values = mapOf(SourceMetadataField.DATE to "2026-08-19T10:20:30+09:00"),
                trackMimeTypes = listOf("video/avc", "audio/mp4a-latm", "application/x-subrip"),
            ),
        )

        assertEquals(
            JsonObject(mapOf("date" to JsonPrimitive("2026-08-19T10:20:30+09:00"))),
            result.metadata,
        )
        assertEquals(
            listOf(
                Diagnostic(
                    "source_metadata_unknown_track",
                    "Source contains an unrecognized non-audio/video track: application/x-subrip",
                ),
            ),
            result.diagnostics,
        )
    }

    @Test
    fun `track inspection failure is diagnostic without hiding readable metadata`() {
        val access = object : SourceMetadataAccess {
            override fun read(field: SourceMetadataField): String? =
                if (field == SourceMetadataField.TITLE) "Readable title" else null

            override fun readXmpBytes(): ByteArray? = null
            override fun readTrackMimeTypes(): List<String> = throw IllegalStateException("unreadable")
        }

        val result = collectSourceMetadata(access)

        assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Readable title"))), result.metadata)
        assertEquals(
            listOf(
                Diagnostic(
                    "source_metadata_track_inspection_failed",
                    "Could not inspect source track metadata",
                ),
            ),
            result.diagnostics,
        )
    }

    @Test
    fun `metadata field cancellation is propagated unchanged`() {
        val cancellation = CancellationException("metadata")
        val access = object : SourceMetadataAccess {
            override fun read(field: SourceMetadataField): String? = throw cancellation
            override fun readXmpBytes(): ByteArray? = null
        }

        val failure = runCatching { collectSourceMetadata(access) }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `XMP cancellation is propagated unchanged`() {
        val cancellation = CancellationException("xmp")
        val access = object : SourceMetadataAccess {
            override fun read(field: SourceMetadataField): String? = null
            override fun readXmpBytes(): ByteArray = throw cancellation
        }

        val failure = runCatching { collectSourceMetadata(access) }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    private class FakeMetadataAccess(
        private val values: Map<SourceMetadataField, String?> = emptyMap(),
        private val xmp: ByteArray? = null,
        private val trackMimeTypes: List<String> = emptyList(),
    ) : SourceMetadataAccess {
        override fun read(field: SourceMetadataField): String? = values[field]
        override fun readXmpBytes(): ByteArray? = xmp
        override fun readTrackMimeTypes(): List<String> = trackMimeTypes
    }
}
