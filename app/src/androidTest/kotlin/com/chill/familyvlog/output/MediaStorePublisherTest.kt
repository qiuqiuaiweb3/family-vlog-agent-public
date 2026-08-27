package com.chill.familyvlog.output

import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.input.FixtureMediaProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePublisherTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test
    fun insertedPendingItem_isHiddenBeforeCommit() {
        val pending = ContentResolverPendingMediaStore(targetContext.contentResolver)
        val uri = requireNotNull(
            pending.insertPending("family-vlog-pending-test-${UUID.randomUUID()}.mp4"),
        )
        try {
            assertEquals(1, pendingValue(uri))
        } finally {
            assertEquals(1, pending.deleteExact(uri))
        }
    }

    @Test
    fun exactPendingItem_isCopiedCommittedAndDeliveredOnMainBeforeReturn() = runBlocking {
        val privateFile = copyFixture()
        var exactUri: Uri? = null
        var callbackReceipt: PublicationReceipt? = null
        var callbackCompleted = false
        try {
            val delegate = ContentResolverPendingMediaStore(targetContext.contentResolver)
            val pending = object : PendingMediaStore {
                override fun insertPending(displayName: String): Uri? =
                    delegate.insertPending(displayName).also { exactUri = it }

                override fun openOutput(uri: Uri) = delegate.openOutput(uri)
                override fun commit(uri: Uri) = delegate.commit(uri)
                override fun deleteExact(uri: Uri) = delegate.deleteExact(uri)
            }
            val publisher = MediaStorePublisher(
                pendingMediaStore = pending,
                ioDispatcher = Dispatchers.IO,
                mainDispatcher = Dispatchers.Main.immediate,
                displayName = { "family-vlog-test-${UUID.randomUUID()}.mp4" },
            )
            val receipt = publisher.publish(
                privateFile = privateFile,
                beforeCommit = {
                    assertTrue(privateFile.delete())
                },
                onCommitted = {
                    callbackReceipt = it
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    callbackCompleted = true
                },
            )

            assertTrue(callbackCompleted)
            assertSame(callbackReceipt, receipt)
            assertFalse(privateFile.exists())
            assertEquals(0, pendingValue(receipt.uri))
            val publishedBytes = requireNotNull(targetContext.contentResolver.openInputStream(receipt.uri)).use {
                it.readBytes()
            }
            assertTrue(publishedBytes.isNotEmpty())
        } finally {
            privateFile.delete()
            exactUri?.let { uri ->
                targetContext.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun copyFixture(): File {
        val output = File(targetContext.cacheDir, "publisher-${UUID.randomUUID()}.mp4")
        instrumentation.context.contentResolver
            .openInputStream(FixtureMediaProvider.uriFor(FixtureMediaProvider.LANDSCAPE_SILENT))
            .use { input ->
                output.outputStream().use { target -> requireNotNull(input).copyTo(target) }
            }
        return output
    }

    private fun pendingValue(uri: Uri): Int = requireNotNull(
        targetContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        ),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
