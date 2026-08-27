package com.chill.familyvlog.subtitle

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import com.chill.familyvlog.render.ActiveExportRegistry
import com.chill.familyvlog.render.ExportHandle
import com.chill.familyvlog.render.ExportHandleFactory
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class Media3SubtitleExporterTest {
    @Test
    fun `font loading preserves cancellation instead of reporting layout failure`() = runTest {
        val cancellation = CancellationException("cancel")

        val failure = runCatching {
            loadSubtitleFont { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `overlay resource is released when cancellation wins after construction`() = runTest {
        val resource = Any()
        var releaseCalls = 0
        var useCalls = 0

        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext()[Job]!!
            withSubtitleRenderingResource(
                create = {
                    owner.cancel(CancellationException("cancel"))
                    resource
                },
                release = { released ->
                    assertSame(resource, released)
                    releaseCalls += 1
                },
                use = {
                    useCalls += 1
                },
            )
        }
        val failure = runCatching {
            operation.await()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, useCalls)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun `subtitle export handle starts exactly once`() = runTest {
        val handle = RecordingHandle()
        lateinit var onCompleted: (ExportResult) -> Unit
        val result = ExportResult.Builder().build()

        val export = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSubtitleExport(
                activeExports = ActiveExportRegistry(),
                handleFactory = ExportHandleFactory { completed, _ ->
                    onCompleted = completed
                    handle
                },
            )
        }
        onCompleted(result)

        assertSame(result, export.await())
        assertEquals(1, handle.startCalls)
        assertEquals(1, handle.removeCalls)
        assertEquals(0, handle.cancelCalls)
    }

    private class RecordingHandle : ExportHandle {
        var startCalls = 0
        var cancelCalls = 0
        var removeCalls = 0

        override fun start() {
            startCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun removeListener() {
            removeCalls += 1
        }
    }
}
