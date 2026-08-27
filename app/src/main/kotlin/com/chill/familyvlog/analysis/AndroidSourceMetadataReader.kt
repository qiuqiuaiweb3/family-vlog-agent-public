package com.chill.familyvlog.analysis

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.system.Os
import com.chill.familyvlog.ai.InlineRequestBudget
import com.chill.familyvlog.contract.Diagnostic
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal fun interface SourceMetadataSessionFactory {
    fun open(uri: Uri): SourceMetadataSession?
}

internal interface SourceMetadataSession : SourceMetadataAccess, Closeable

class AndroidSourceMetadataReader internal constructor(
    private val sessionFactory: SourceMetadataSessionFactory,
    private val dispatcher: CoroutineDispatcher,
) : SourceMetadataReader {
    constructor(context: Context) : this(
        sessionFactory = PlatformSourceMetadataSessionFactory(context.contentResolver),
        dispatcher = Dispatchers.IO,
    )

    override suspend fun read(uri: Uri): SourceMetadataReadResult = withContext(dispatcher) {
        try {
            sessionFactory.open(uri)?.use(::collectSourceMetadata) ?: unreadableMetadata()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            unreadableMetadata()
        }
    }

    private fun unreadableMetadata() = SourceMetadataReadResult(
        metadata = JsonObject(emptyMap()),
        diagnostics = listOf(
            Diagnostic(
                "source_metadata_read_failed",
                "Could not read source metadata",
            ),
        ),
    )
}

private class PlatformSourceMetadataSessionFactory(
    private val contentResolver: ContentResolver,
) : SourceMetadataSessionFactory {
    override fun open(uri: Uri): SourceMetadataSession? {
        val descriptor = contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
        val retriever = try {
            MediaMetadataRetriever()
        } catch (failure: Throwable) {
            closeAfterFailure(descriptor, failure)
            throw failure
        }
        val session = PlatformSourceMetadataSession(descriptor, retriever)
        try {
            retriever.setDataSource(
                descriptor.fileDescriptor,
                descriptor.startOffset,
                descriptor.length,
            )
            session.initializeTrackReader()
        } catch (failure: Throwable) {
            closeAfterFailure(session, failure)
            throw failure
        }
        return session
    }

    private fun closeAfterFailure(
        resource: Closeable,
        failure: Throwable,
    ) {
        try {
            resource.close()
        } catch (cancellation: CancellationException) {
            if (failure is CancellationException) {
                if (cancellation !== failure) failure.addSuppressed(cancellation)
            } else {
                throw cancellation
            }
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }
}

private class PlatformSourceMetadataSession(
    private val descriptor: AssetFileDescriptor,
    private val retriever: MediaMetadataRetriever,
) : SourceMetadataSession {
    private var extractor: MediaExtractor? = null
    private var trackReaderFailure: Exception? = null

    fun initializeTrackReader() {
        val candidate = MediaExtractor()
        try {
            candidate.setDataSource(
                descriptor.fileDescriptor,
                descriptor.startOffset,
                descriptor.length,
            )
            extractor = candidate
        } catch (cancellation: CancellationException) {
            candidate.release()
            throw cancellation
        } catch (failure: Exception) {
            candidate.release()
            trackReaderFailure = failure
        }
    }

    override fun read(field: SourceMetadataField): String? = retriever.extractMetadata(
        field.androidMetadataKey(),
    )

    override fun readXmpBytes(): ByteArray? {
        val offsetValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_XMP_OFFSET)
        val lengthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_XMP_LENGTH)
        if (offsetValue == null && lengthValue == null) return null
        val relativeOffset = requireNotNull(offsetValue).toLong()
        val length = requireNotNull(lengthValue).toLong()
        return requireNotNull(readDescriptorRange(descriptor, relativeOffset, length))
    }

    override fun readTrackMimeTypes(): List<String> {
        trackReaderFailure?.let { throw it }
        val currentExtractor = extractor ?: return emptyList()
        return List(currentExtractor.trackCount) { trackIndex ->
            requireNotNull(currentExtractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME))
        }
    }

    override fun close() {
        var cancellation: CancellationException? = null
        try {
            extractor?.release()
        } catch (failure: CancellationException) {
            cancellation = failure
        } catch (_: Exception) {
        }
        try {
            retriever.close()
        } catch (failure: CancellationException) {
            if (cancellation == null) {
                cancellation = failure
            } else if (failure !== cancellation) {
                cancellation.addSuppressed(failure)
            }
        } catch (_: Exception) {
        }
        try {
            descriptor.close()
        } catch (failure: CancellationException) {
            if (cancellation == null) {
                cancellation = failure
            } else if (failure !== cancellation) {
                cancellation.addSuppressed(failure)
            }
        } catch (_: Exception) {
        }
        cancellation?.let { throw it }
    }
}

internal fun readDescriptorRange(
    descriptor: AssetFileDescriptor,
    relativeOffset: Long,
    length: Long,
): ByteArray? {
    if (relativeOffset < 0L || length < 0L || length > MAX_READABLE_SOURCE_XMP_BYTES) return null
    val descriptorLength = descriptor.length
    if (
        descriptorLength != AssetFileDescriptor.UNKNOWN_LENGTH &&
        (relativeOffset > descriptorLength || length > descriptorLength - relativeOffset)
    ) {
        return null
    }
    val absoluteOffset = try {
        Math.addExact(descriptor.startOffset, relativeOffset)
    } catch (_: ArithmeticException) {
        return null
    }
    try {
        Math.addExact(absoluteOffset, length)
    } catch (_: ArithmeticException) {
        return null
    }
    val bytes = ByteArray(length.toInt())
    var bytesRead = 0
    while (bytesRead < bytes.size) {
        val count = Os.pread(
            descriptor.fileDescriptor,
            bytes,
            bytesRead,
            bytes.size - bytesRead,
            absoluteOffset + bytesRead,
        )
        if (count <= 0) return null
        bytesRead += count
    }
    return bytes
}

internal const val MAX_READABLE_SOURCE_XMP_BYTES =
    InlineRequestBudget.REQUEST_LIMIT_BYTES -
        InlineRequestBudget.FIXED_SDK_SCHEMA_WRAPPER_ALLOWANCE_BYTES -
        1L

internal fun SourceMetadataField.androidMetadataKey(): Int = when (this) {
    SourceMetadataField.DATE -> MediaMetadataRetriever.METADATA_KEY_DATE
    SourceMetadataField.LOCATION -> MediaMetadataRetriever.METADATA_KEY_LOCATION
    SourceMetadataField.AUTHOR -> MediaMetadataRetriever.METADATA_KEY_AUTHOR
    SourceMetadataField.WRITER -> MediaMetadataRetriever.METADATA_KEY_WRITER
    SourceMetadataField.TITLE -> MediaMetadataRetriever.METADATA_KEY_TITLE
    SourceMetadataField.ARTIST -> MediaMetadataRetriever.METADATA_KEY_ARTIST
    SourceMetadataField.ALBUM -> MediaMetadataRetriever.METADATA_KEY_ALBUM
    SourceMetadataField.ALBUMARTIST -> MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
    SourceMetadataField.COMPOSER -> MediaMetadataRetriever.METADATA_KEY_COMPOSER
    SourceMetadataField.GENRE -> MediaMetadataRetriever.METADATA_KEY_GENRE
    SourceMetadataField.YEAR -> MediaMetadataRetriever.METADATA_KEY_YEAR
}
