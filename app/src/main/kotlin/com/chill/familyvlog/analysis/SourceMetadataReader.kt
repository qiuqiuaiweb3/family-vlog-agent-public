package com.chill.familyvlog.analysis

import android.net.Uri
import com.chill.familyvlog.contract.Diagnostic
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface SourceMetadataReader {
    suspend fun read(uri: Uri): SourceMetadataReadResult
}

data class SourceMetadataReadResult(
    val metadata: JsonObject,
    val diagnostics: List<Diagnostic>,
)

internal enum class SourceMetadataField(val jsonName: String) {
    DATE("date"),
    LOCATION("location"),
    AUTHOR("author"),
    WRITER("writer"),
    TITLE("title"),
    ARTIST("artist"),
    ALBUM("album"),
    ALBUMARTIST("albumartist"),
    COMPOSER("composer"),
    GENRE("genre"),
    YEAR("year"),
}

internal interface SourceMetadataAccess {
    fun read(field: SourceMetadataField): String?
    fun readXmpBytes(): ByteArray?
    fun readTrackMimeTypes(): List<String> = emptyList()
}

internal fun collectSourceMetadata(access: SourceMetadataAccess): SourceMetadataReadResult {
    val values = linkedMapOf<String, JsonPrimitive>()
    val diagnostics = mutableListOf<Diagnostic>()
    SourceMetadataField.entries.forEach { field ->
        try {
            access.read(field)?.let { value -> values[field.jsonName] = JsonPrimitive(value) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            diagnostics += Diagnostic(
                "source_metadata_field_unreadable",
                "Could not read source metadata field: ${field.jsonName}",
            )
        }
    }
    try {
        access.readXmpBytes()
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { bytes ->
                val xmp = bytes.decodeStrictUtf8OrNull()
                if (xmp == null) {
                    diagnostics += Diagnostic(
                        "source_metadata_xmp_unreadable",
                        "Could not decode source XMP metadata as UTF-8",
                    )
                } else {
                    values["xmp"] = JsonPrimitive(xmp)
                }
            }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        diagnostics += Diagnostic(
            "source_metadata_xmp_unreadable",
            "Could not read source XMP metadata",
        )
    }
    try {
        access.readTrackMimeTypes()
            .filterNot { mimeType -> mimeType.startsWith("video/") || mimeType.startsWith("audio/") }
            .forEach { mimeType ->
                diagnostics += Diagnostic(
                    "source_metadata_unknown_track",
                    "Source contains an unrecognized non-audio/video track: $mimeType",
                )
            }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        diagnostics += Diagnostic(
            "source_metadata_track_inspection_failed",
            "Could not inspect source track metadata",
        )
    }
    return SourceMetadataReadResult(JsonObject(values), diagnostics)
}

private fun ByteArray.decodeStrictUtf8OrNull(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: Exception) {
    null
}
