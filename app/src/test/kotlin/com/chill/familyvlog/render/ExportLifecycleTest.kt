package com.chill.familyvlog.render

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class ExportLifecycleTest {
    @Test
    fun `completion accepts a legal Media3 format fallback and releases the listener`() = runBlocking {
        supervisorScope {
            val fixture = Fixture()
            val export = async(start = CoroutineStart.UNDISPATCHED) { fixture.await() }
            val fallbackResult = ExportResult.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setAudioMimeType(MimeTypes.AUDIO_OPUS)
                .build()

            fixture.complete(fallbackResult)

            assertSame(fallbackResult, export.await())
            assertEquals(1, fixture.handle.startCalls)
            assertEquals(1, fixture.handle.removeCalls)
            assertEquals(0, fixture.handle.cancelCalls)
        }
    }

    @Test
    fun `error callback preserves the Media3 cause and releases the listener`() = runBlocking {
        supervisorScope {
            val fixture = Fixture()
            val export = async(start = CoroutineStart.UNDISPATCHED) { fixture.await() }
            val media3Failure = mock(ExportException::class.java)

            fixture.error(media3Failure)

            val failure = assertRenderFailure { export.await() }
            assertSame(media3Failure, failure.cause)
            assertEquals(1, fixture.handle.removeCalls)
            assertEquals(0, fixture.handle.cancelCalls)
        }
    }

    @Test
    fun `synchronous start failure cancels resources and leaves the registry reusable`() = runBlocking {
        val registry = ActiveExportRegistry<ExportHandle>()
        val startFailure = IllegalStateException("start")
        val failed = Fixture(registry, startFailure)

        val failure = assertRenderFailure { failed.await() }
        assertSame(startFailure, failure.cause)
        assertEquals(1, failed.handle.startCalls)
        assertEquals(1, failed.handle.cancelCalls)
        assertEquals(1, failed.handle.removeCalls)

        val reused = Fixture(registry)
        val export = async(start = CoroutineStart.UNDISPATCHED) { reused.await() }
        val result = ExportResult.Builder().build()
        reused.complete(result)
        assertSame(result, export.await())
    }

    @Test
    fun `cancellation cancels resources while a concurrent export fails without starting`() = runBlocking {
        val registry = ActiveExportRegistry<ExportHandle>()
        val first = Fixture(registry)
        val firstExport = async(start = CoroutineStart.UNDISPATCHED) { first.await() }
        val second = Fixture(registry)

        assertRenderFailure { second.await() }
        assertEquals(0, second.handle.startCalls)
        assertEquals(0, second.handle.cancelCalls)
        assertEquals(1, second.handle.removeCalls)

        firstExport.cancelAndJoin()
        assertTrue(firstExport.isCancelled)
        assertEquals(1, first.handle.cancelCalls)
        assertEquals(1, first.handle.removeCalls)

        val reused = Fixture(registry)
        val reusedExport = async(start = CoroutineStart.UNDISPATCHED) { reused.await() }
        val result = ExportResult.Builder().build()
        reused.complete(result)
        assertSame(result, reusedExport.await())
    }

    @Test
    fun `late callback from a cancelled export cannot affect a later export`() = runBlocking {
        val registry = ActiveExportRegistry<ExportHandle>()
        val first = Fixture(registry)
        val firstExport = async(start = CoroutineStart.UNDISPATCHED) { first.await() }

        firstExport.cancelAndJoin()

        val later = Fixture(registry)
        val laterExport = async(start = CoroutineStart.UNDISPATCHED) { later.await() }
        first.complete(ExportResult.Builder().build())
        assertFalse(laterExport.isCompleted)
        val result = ExportResult.Builder().build()
        later.complete(result)
        assertSame(result, laterExport.await())
    }

    private suspend fun assertRenderFailure(block: suspend () -> Unit): PipelineException {
        try {
            block()
            fail("Expected render failure")
        } catch (failure: PipelineException) {
            assertEquals(RunPhase.RENDERING, failure.phase)
            assertEquals(RunFailureCode.RENDER_FAILED, failure.code)
            return failure
        }
        error("unreachable")
    }

    private class Fixture(
        private val registry: ActiveExportRegistry<ExportHandle> = ActiveExportRegistry(),
        startFailure: Exception? = null,
    ) {
        lateinit var complete: (ExportResult) -> Unit
        lateinit var error: (ExportException) -> Unit
        val handle = FakeHandle(startFailure)

        suspend fun await(): ExportResult = awaitExport(
            activeExports = registry,
            handleFactory = ExportHandleFactory { onCompleted, onError ->
                complete = onCompleted
                error = onError
                handle
            },
        )
    }

    private class FakeHandle(private val startFailure: Exception?) : ExportHandle {
        var startCalls = 0
        var cancelCalls = 0
        var removeCalls = 0

        override fun start() {
            startCalls += 1
            startFailure?.let { throw it }
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun removeListener() {
            removeCalls += 1
        }
    }
}
