package com.chill.familyvlog.output

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.render.RenderSpec
import com.chill.familyvlog.render.Renderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class ExportCoordinatorTest {
    private val root = Files.createTempDirectory("export-coordinator-test").toFile()
    private val spec = RenderSpec(emptyList(), expectsAudio = false, canvasAspectRatio = 1f)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `inspection gates delete only the exact private output and never start saving`() = runBlocking {
        val invalid = listOf(
            OutputInspection(nonEmpty = false, parseable = false, hasVideo = false, hasAudio = false),
            OutputInspection(nonEmpty = true, parseable = false, hasVideo = false, hasAudio = false),
            OutputInspection(nonEmpty = true, parseable = true, hasVideo = false, hasAudio = false),
            OutputInspection(nonEmpty = true, parseable = true, hasVideo = true, hasAudio = false),
        )

        invalid.forEachIndexed { index, inspection ->
            val expectsAudio = index == invalid.lastIndex
            val fixture = CoordinatorFixture(inspection = inspection)
            val sentinel = File(root, "sentinel-$index").apply { writeText("keep") }

            assertPipelineFailure(RunPhase.RENDERING, RunFailureCode.OUTPUT_INSPECTION_FAILED) {
                fixture.coordinator.export(spec.copy(expectsAudio = expectsAudio))
            }

            assertFalse(fixture.output.exists())
            assertTrue(sentinel.exists())
            assertEquals(0, fixture.publisher.calls)
            assertEquals(0, fixture.savingCalls)
            assertEquals(0, fixture.committedCalls)
        }
    }

    @Test
    fun `silent output passes when audio is not expected`() = runBlocking {
        val fixture = CoordinatorFixture(
            inspection = OutputInspection(nonEmpty = true, parseable = true, hasVideo = true, hasAudio = false),
        )

        val receipt = fixture.coordinator.export(spec)

        assertSame(fixture.publisher.receipt, receipt)
        assertEquals(1, fixture.savingCalls)
        assertEquals(1, fixture.committedCalls)
        assertFalse(fixture.output.exists())
    }

    @Test
    fun `render and inspection cancellation preserve the exact cause after private cleanup`() = runBlocking {
        val renderCancellation = CancellationException("render")
        val renderFixture = CoordinatorFixture(renderFailure = renderCancellation)
        assertSameCancellation(renderCancellation) { renderFixture.coordinator.export(spec) }
        assertFalse(renderFixture.output.exists())

        val inspectionCancellation = CancellationException("inspect")
        val inspectionFixture = CoordinatorFixture(inspectionFailure = inspectionCancellation)
        assertSameCancellation(inspectionCancellation) { inspectionFixture.coordinator.export(spec) }
        assertFalse(inspectionFixture.output.exists())
    }

    @Test
    fun `inspection cannot return a valid result after cancelling its caller`() = runBlocking {
        val cancellation = CancellationException("inspection-return-race")
        val fixture = CoordinatorFixture(
            inspectionAction = {
                currentCoroutineContext()[Job]!!.cancel(cancellation)
            },
        )

        supervisorScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.export(spec)
            }
            assertSameCancellation(cancellation) { result.await() }
        }

        assertFalse(fixture.output.exists())
        assertEquals(0, fixture.publisher.calls)
        assertEquals(0, fixture.savingCalls)
        assertEquals(0, fixture.committedCalls)
    }

    @Test
    fun `private cleanup failure overrides a rendering failure`() = runBlocking {
        val fixture = CoordinatorFixture(
            renderFailure = IllegalStateException("render"),
            deletePrivate = false,
        )

        assertPipelineFailure(RunPhase.RENDERING, RunFailureCode.PRIVATE_STORAGE_FAILED) {
            fixture.coordinator.export(spec)
        }
        assertTrue(fixture.output.exists())
    }

    @Test
    fun `private cleanup failure overrides actual job cancellation`() = runBlocking {
        val output = File(root, "cancel-private-failure.mp4")
        val entered = CompletableDeferred<Unit>()
        val cancellation = CancellationException("actual-render-cancellation")
        val coordinator = ExportCoordinator(
            renderer = object : Renderer {
                override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                    output.writeBytes(byteArrayOf(1))
                    entered.complete(Unit)
                    awaitCancellation()
                }
            },
            inspector = OutputInspector { OutputInspection(true, true, true, false) },
            publisher = FakePublisher(mock(Uri::class.java)),
            privateOutputStore = object : PrivateOutputStore {
                override fun create(): File = output
                override fun deleteExact(file: File): Boolean = false
            },
            onSaving = {},
            onCommitted = {},
        )

        supervisorScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) { coordinator.export(spec) }
            entered.await()
            result.cancel(cancellation)
            val failure = assertPipelineFailure(RunPhase.RENDERING, RunFailureCode.PRIVATE_STORAGE_FAILED) {
                result.await()
            }
            assertTrue(failure.suppressed.any {
                generateSequence(it) { nested -> nested.cause }.any { nested -> nested === cancellation }
            })
        }

        assertTrue(output.exists())
    }

    @Test
    fun `ordinary render and inspection errors map to their exact gates`() = runBlocking {
        val renderFixture = CoordinatorFixture(renderFailure = IllegalStateException("render"))
        assertPipelineFailure(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED) {
            renderFixture.coordinator.export(spec)
        }
        assertFalse(renderFixture.output.exists())

        val inspectionFixture = CoordinatorFixture(inspectionFailure = IllegalStateException("inspect"))
        assertPipelineFailure(RunPhase.RENDERING, RunFailureCode.OUTPUT_INSPECTION_FAILED) {
            inspectionFixture.coordinator.export(spec)
        }
        assertFalse(inspectionFixture.output.exists())
    }

    @Test
    fun `publisher success follows the exact copy delete gate commit callback order`() = runBlocking {
        val events = mutableListOf<String>()
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri, events = events)
        val privateFile = File(root, "ordered.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val publisher = MediaStorePublisher(
            pendingMediaStore = pending,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            displayName = { "ordered.mp4" },
        )

        events += "publish"
        val receipt = publisher.publish(
            privateFile = privateFile,
            beforeCommit = {
                events += "deletePrivate"
                assertTrue(privateFile.delete())
            },
            onCommitted = { events += "onCommitted" },
        )
        events += "return"

        assertSame(uri, receipt.uri)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), pending.written.toByteArray().toList())
        assertEquals(
            listOf(
                "publish",
                "insert",
                "open",
                "closeOutput",
                "deletePrivate",
                "commit",
                "onCommitted",
                "return",
            ),
            events,
        )
        assertEquals(0, pending.deleteCalls)
    }

    @Test
    fun `every non exact update result cleans only the inserted uri and fails publication`() = runBlocking {
        listOf(0, 2, -1).forEach { outcome ->
            val uri = mock(Uri::class.java)
            val other = mock(Uri::class.java)
            val pending = FakePendingMediaStore(uri, commitResult = outcome)
            val privateFile = File(root, "update-$outcome.mp4").apply { writeBytes(byteArrayOf(9)) }
            val publisher = publisher(pending)
            var committedCalls = 0

            assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
                publisher.publish(
                    privateFile,
                    beforeCommit = { assertTrue(privateFile.delete()) },
                    onCommitted = { committedCalls += 1 },
                )
            }

            assertEquals(1, pending.deleteCalls)
            assertSame(uri, pending.deletedUri)
            assertFalse(pending.touchedUris.any { it === other })
            assertEquals(0, committedCalls)
        }
    }

    @Test
    fun `update exception cleans the inserted uri and maps to publication failure`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri, commitFailure = IllegalStateException("update"))
        val privateFile = File(root, "update-throw.mp4").apply { writeBytes(byteArrayOf(9)) }
        var committedCalls = 0

        assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
            publisher(pending).publish(
                privateFile,
                beforeCommit = { assertTrue(privateFile.delete()) },
                onCommitted = { committedCalls += 1 },
            )
        }

        assertEquals(1, pending.deleteCalls)
        assertSame(uri, pending.deletedUri)
        assertEquals(0, committedCalls)
    }

    @Test
    fun `insert and open failures never scan and clean only when an exact uri exists`() = runBlocking {
        val uri = mock(Uri::class.java)
        val insertNull = FakePendingMediaStore(uri, insertResult = null)
        val insertFile = File(root, "insert-null.mp4").apply { writeBytes(byteArrayOf(1)) }
        assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
            publisher(insertNull).publish(insertFile, beforeCommit = {}, onCommitted = {})
        }
        assertEquals(0, insertNull.deleteCalls)

        listOf(
            FakePendingMediaStore(uri, openReturnsNull = true),
            FakePendingMediaStore(uri, openFailure = IllegalStateException("open")),
            FakePendingMediaStore(uri, output = object : ByteArrayOutputStream() {
                override fun close() = throw IllegalStateException("close")
            }),
        ).forEachIndexed { index, pending ->
            val privateFile = File(root, "open-$index.mp4").apply { writeBytes(byteArrayOf(1)) }
            assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
                publisher(pending).publish(privateFile, beforeCommit = {}, onCommitted = {})
            }
            assertEquals(1, pending.deleteCalls)
            assertSame(uri, pending.deletedUri)
        }
    }

    @Test
    fun `coordinator deletes private output across every precommit publisher failure`() = runBlocking {
        data class FailureCase(
            val label: String,
            val expectedPendingDeletes: Int,
            val cancellation: CancellationException? = null,
            val pending: (Uri, MutableList<String>) -> FakePendingMediaStore,
        )

        val insertCancellation = CancellationException("insert")
        val openCancellation = CancellationException("open")
        val copyCancellation = CancellationException("copy")
        val closeCancellation = CancellationException("close")
        val cases = listOf(
            FailureCase("insert-null", 0) { uri, events ->
                FakePendingMediaStore(uri, insertResult = null, events = events)
            },
            FailureCase("insert-throw", 0) { uri, events ->
                FakePendingMediaStore(uri, insertFailure = IllegalStateException("insert"), events = events)
            },
            FailureCase("insert-cancel", 0, insertCancellation) { uri, events ->
                FakePendingMediaStore(uri, insertFailure = insertCancellation, events = events)
            },
            FailureCase("open-null", 1) { uri, events ->
                FakePendingMediaStore(uri, openReturnsNull = true, events = events)
            },
            FailureCase("open-throw", 1) { uri, events ->
                FakePendingMediaStore(uri, openFailure = IllegalStateException("open"), events = events)
            },
            FailureCase("open-cancel", 1, openCancellation) { uri, events ->
                FakePendingMediaStore(uri, openFailure = openCancellation, events = events)
            },
            FailureCase("copy-throw", 1) { uri, events ->
                FakePendingMediaStore(
                    uri,
                    output = failingOutput(IllegalStateException("copy")),
                    events = events,
                )
            },
            FailureCase("copy-cancel", 1, copyCancellation) { uri, events ->
                FakePendingMediaStore(uri, output = failingOutput(copyCancellation), events = events)
            },
            FailureCase("close-throw", 1) { uri, events ->
                FakePendingMediaStore(
                    uri,
                    output = closingOutput(IllegalStateException("close")),
                    events = events,
                )
            },
            FailureCase("close-cancel", 1, closeCancellation) { uri, events ->
                FakePendingMediaStore(uri, output = closingOutput(closeCancellation), events = events)
            },
        )

        cases.forEach { case ->
            val uri = mock(Uri::class.java)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val pending = case.pending(uri, events)
            val output = File(root, "precommit-${case.label}.mp4")
            var savingCalls = 0
            var committedCalls = 0
            val coordinator = ExportCoordinator(
                renderer = object : Renderer {
                    override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                        output.writeBytes(byteArrayOf(1, 2, 3))
                        return ExportResult.Builder().build()
                    }
                },
                inspector = OutputInspector { OutputInspection(true, true, true, false) },
                publisher = publisher(pending),
                privateOutputStore = object : PrivateOutputStore {
                    override fun create(): File = output
                    override fun deleteExact(file: File): Boolean {
                        events += "deletePrivate"
                        return !file.exists() || file.delete()
                    }
                },
                onSaving = { savingCalls += 1 },
                onCommitted = { committedCalls += 1 },
            )

            if (case.cancellation == null) {
                assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
                    coordinator.export(spec)
                }
            } else {
                assertSameCancellation(case.cancellation) { coordinator.export(spec) }
            }

            assertFalse(case.label, output.exists())
            assertEquals(case.label, case.expectedPendingDeletes, pending.deleteCalls)
            assertEquals(case.label, 0, pending.commitCalls)
            if (case.expectedPendingDeletes == 1) assertSame(uri, pending.deletedUri)
            val cleanupEvents = events.filter { it == "deletePending" || it == "deletePrivate" }
            assertEquals(
                case.label,
                if (case.expectedPendingDeletes == 1) {
                    listOf("deletePending", "deletePrivate")
                } else {
                    listOf("deletePrivate")
                },
                cleanupEvents,
            )
            assertEquals(case.label, 1, savingCalls)
            assertEquals(case.label, 0, committedCalls)
        }
    }

    @Test
    fun `copy cancellation cleans the exact pending uri and preserves the cause`() = runBlocking {
        val cancellation = CancellationException("copy")
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(
            uri = uri,
            output = object : OutputStream() {
                override fun write(value: Int) = throw cancellation
                override fun write(bytes: ByteArray, offset: Int, length: Int) = throw cancellation
            },
        )
        val privateFile = File(root, "cancel-copy.mp4").apply { writeBytes(byteArrayOf(1)) }

        assertSameCancellation(cancellation) {
            publisher(pending).publish(privateFile, beforeCommit = { fail("must not commit") }, onCommitted = {})
        }

        assertEquals(1, pending.deleteCalls)
        assertSame(uri, pending.deletedUri)
    }

    @Test
    fun `pending cleanup failure outranks private cleanup failure and retains it as diagnosis`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri = uri, deleteResult = 0)
        val privateFailure = PipelineException(RunPhase.SAVING, RunFailureCode.PRIVATE_STORAGE_FAILED)
        val privateFile = File(root, "double-failure.mp4").apply { writeBytes(byteArrayOf(1)) }
        var committedCalls = 0

        val failure = assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
            publisher(pending).publish(
                privateFile,
                beforeCommit = { throw privateFailure },
                onCommitted = { committedCalls += 1 },
            )
        }

        assertTrue(failure.suppressed.any { it === privateFailure })
        assertEquals(0, pending.commitCalls)
        assertEquals(0, committedCalls)
    }

    @Test
    fun `private cleanup failure remains primary when pending cleanup succeeds`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri = uri)
        val privateFailure = PipelineException(RunPhase.SAVING, RunFailureCode.PRIVATE_STORAGE_FAILED)
        val privateFile = File(root, "private-failure.mp4").apply { writeBytes(byteArrayOf(1)) }
        var committedCalls = 0

        val failure = assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PRIVATE_STORAGE_FAILED) {
            publisher(pending).publish(
                privateFile,
                beforeCommit = { throw privateFailure },
                onCommitted = { committedCalls += 1 },
            )
        }

        assertSame(privateFailure, failure)
        assertEquals(1, pending.deleteCalls)
        assertEquals(0, pending.commitCalls)
        assertEquals(0, committedCalls)
    }

    @Test
    fun `private cleanup failure outranks an ordinary publication failure after pending cleanup succeeds`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri = uri, openReturnsNull = true)
        val output = File(root, "ordinary-publish-private-failure.mp4")
        var committedCalls = 0
        val store = object : PrivateOutputStore {
            override fun create(): File = output
            override fun deleteExact(file: File): Boolean = false
        }
        val coordinator = ExportCoordinator(
            renderer = object : Renderer {
                override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                    output.writeBytes(byteArrayOf(1))
                    return ExportResult.Builder().build()
                }
            },
            inspector = OutputInspector { OutputInspection(true, true, true, false) },
            publisher = publisher(pending),
            privateOutputStore = store,
            onSaving = {},
            onCommitted = { committedCalls += 1 },
        )

        val failure = assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PRIVATE_STORAGE_FAILED) {
            coordinator.export(spec)
        }

        assertTrue(failure.suppressed.any {
            it is PipelineException &&
                it.phase == RunPhase.SAVING &&
                it.code == RunFailureCode.PUBLISH_FAILED
        })
        assertEquals(1, pending.deleteCalls)
        assertEquals(0, committedCalls)
        assertTrue(output.exists())
    }

    @Test
    fun `private cleanup failure after saving overrides actual job cancellation`() = runBlocking {
        val output = File(root, "saving-cancel-private-failure.mp4")
        val cancellation = CancellationException("actual-saving-cancellation")
        var savingCalls = 0
        val coordinator = ExportCoordinator(
            renderer = object : Renderer {
                override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                    output.writeBytes(byteArrayOf(1))
                    return ExportResult.Builder().build()
                }
            },
            inspector = OutputInspector { OutputInspection(true, true, true, false) },
            publisher = object : Publisher {
                override suspend fun publish(
                    privateFile: File,
                    beforeCommit: () -> Unit,
                    onCommitted: (PublicationReceipt) -> Unit,
                ): PublicationReceipt {
                    currentCoroutineContext()[Job]!!.cancel(cancellation)
                    awaitCancellation()
                }
            },
            privateOutputStore = object : PrivateOutputStore {
                override fun create(): File = output
                override fun deleteExact(file: File): Boolean = false
            },
            onSaving = { savingCalls += 1 },
            onCommitted = {},
        )

        supervisorScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) { coordinator.export(spec) }
            val failure = assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PRIVATE_STORAGE_FAILED) {
                result.await()
            }
            assertTrue(failure.suppressed.any {
                generateSequence(it) { nested -> nested.cause }.any { nested -> nested === cancellation }
            })
        }

        assertEquals(1, savingCalls)
        assertTrue(output.exists())
    }

    @Test
    fun `pending cleanup failure still outranks coordinator private cleanup failure`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri = uri, openReturnsNull = true, deleteResult = 0)
        val output = File(root, "pending-and-private-failure.mp4")
        var committedCalls = 0
        val store = object : PrivateOutputStore {
            override fun create(): File = output
            override fun deleteExact(file: File): Boolean = false
        }
        val coordinator = ExportCoordinator(
            renderer = object : Renderer {
                override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                    output.writeBytes(byteArrayOf(1))
                    return ExportResult.Builder().build()
                }
            },
            inspector = OutputInspector { OutputInspection(true, true, true, false) },
            publisher = publisher(pending),
            privateOutputStore = store,
            onSaving = {},
            onCommitted = { committedCalls += 1 },
        )

        val failure = assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) {
            coordinator.export(spec)
        }

        assertTrue(failure.suppressed.any {
            it is PipelineException &&
                it.phase == RunPhase.SAVING &&
                it.code == RunFailureCode.PRIVATE_STORAGE_FAILED
        })
        assertEquals(1, pending.deleteCalls)
        assertEquals(0, committedCalls)
        assertTrue(output.exists())
    }

    @Test
    fun `cancellation after private deletion is stopped by the final gate before update`() = runBlocking {
        val uri = mock(Uri::class.java)
        val pending = FakePendingMediaStore(uri)
        val privateFile = File(root, "last-gate.mp4").apply { writeBytes(byteArrayOf(1)) }
        val cancellation = CancellationException("last-gate")

        supervisorScope {
            lateinit var result: Deferred<PublicationReceipt>
            result = async(start = CoroutineStart.LAZY) {
                publisher(pending).publish(
                    privateFile,
                    beforeCommit = {
                        assertTrue(privateFile.delete())
                        result.cancel(cancellation)
                    },
                    onCommitted = {},
                )
            }
            result.start()
            assertSameCancellation(cancellation) { result.await() }
        }

        assertEquals(0, pending.commitCalls)
        assertEquals(1, pending.deleteCalls)
        assertSame(uri, pending.deletedUri)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation during a successful blocking update still commits and calls back`() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "publisher-io") }
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "publisher-main") }
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val uri = mock(Uri::class.java)
            val pending = FakePendingMediaStore(uri = uri, commitBlock = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                1
            })
            val privateFile = File(root, "cancel-success.mp4").apply { writeBytes(byteArrayOf(1)) }
            val committed = AtomicReference<PublicationReceipt>()
            val callbackThread = AtomicReference<String>()
            val publisher = MediaStorePublisher(pending, ioDispatcher, mainDispatcher) { "cancel-success.mp4" }
            val cancellation = CancellationException("during-update")

            supervisorScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.publish(
                        privateFile,
                        beforeCommit = { assertTrue(privateFile.delete()) },
                        onCommitted = {
                            committed.set(it)
                            callbackThread.set(Thread.currentThread().name)
                        },
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                result.cancel(cancellation)
                release.countDown()
                assertSameCancellation(cancellation) { result.await() }
            }

            assertSame(uri, committed.get().uri)
            assertTrue(callbackThread.get().startsWith("publisher-main"))
            assertEquals(0, pending.deleteCalls)
            assertFalse(privateFile.exists())
        } finally {
            ioDispatcher.close()
            mainDispatcher.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation during a failed blocking update cleans then preserves the exact cause`() = runBlocking {
        listOf(0, 2, -1).forEach { outcome ->
            val ioExecutor = Executors.newSingleThreadExecutor()
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()
            try {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val uri = mock(Uri::class.java)
                val pending = FakePendingMediaStore(uri = uri, commitBlock = {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    outcome
                })
                val privateFile = File(root, "cancel-failure-$outcome.mp4").apply { writeBytes(byteArrayOf(1)) }
                val publisher = MediaStorePublisher(
                    pending,
                    ioDispatcher,
                    kotlinx.coroutines.Dispatchers.Unconfined,
                ) { "cancel-failure.mp4" }
                val cancellation = CancellationException("during-update-$outcome")
                var committedCalls = 0

                supervisorScope {
                    val result = async(start = CoroutineStart.UNDISPATCHED) {
                        publisher.publish(
                            privateFile,
                            beforeCommit = { assertTrue(privateFile.delete()) },
                            onCommitted = { committedCalls += 1 },
                        )
                    }
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    result.cancel(cancellation)
                    release.countDown()
                    assertSameCancellation(cancellation) { result.await() }
                }

                assertEquals(1, pending.deleteCalls)
                assertSame(uri, pending.deletedUri)
                assertEquals(0, committedCalls)
            } finally {
                ioDispatcher.close()
                ioExecutor.shutdownNow()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation during a throwing blocking update cleans then preserves the exact cause`() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val uri = mock(Uri::class.java)
            val pending = FakePendingMediaStore(uri = uri, commitBlock = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                throw IllegalStateException("update")
            })
            val privateFile = File(root, "cancel-throwing-update.mp4").apply { writeBytes(byteArrayOf(1)) }
            val publisher = MediaStorePublisher(
                pending,
                ioDispatcher,
                kotlinx.coroutines.Dispatchers.Unconfined,
            ) { "cancel-throwing-update.mp4" }
            val cancellation = CancellationException("during-throwing-update")
            var committedCalls = 0

            supervisorScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.publish(
                        privateFile,
                        beforeCommit = { assertTrue(privateFile.delete()) },
                        onCommitted = { committedCalls += 1 },
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                result.cancel(cancellation)
                release.countDown()
                assertSameCancellation(cancellation) { result.await() }
            }

            assertEquals(1, pending.deleteCalls)
            assertSame(uri, pending.deletedUri)
            assertEquals(0, committedCalls)
        } finally {
            ioDispatcher.close()
            ioExecutor.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `pending cleanup failure still overrides cancellation across the io dispatcher`() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val uri = mock(Uri::class.java)
            val pending = FakePendingMediaStore(
                uri = uri,
                commitBlock = {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    0
                },
                deleteResult = 0,
            )
            val privateFile = File(root, "cancel-cleanup-failure.mp4").apply { writeBytes(byteArrayOf(1)) }
            val publisher = MediaStorePublisher(
                pending,
                ioDispatcher,
                kotlinx.coroutines.Dispatchers.Unconfined,
            ) { "cancel-cleanup-failure.mp4" }
            val cancellation = CancellationException("cleanup-failure-must-win")

            supervisorScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.publish(
                        privateFile,
                        beforeCommit = { assertTrue(privateFile.delete()) },
                        onCommitted = {},
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                result.cancel(cancellation)
                release.countDown()
                assertPipelineFailure(RunPhase.SAVING, RunFailureCode.PUBLISH_FAILED) { result.await() }
            }

            assertEquals(1, pending.deleteCalls)
            assertSame(uri, pending.deletedUri)
        } finally {
            ioDispatcher.close()
            ioExecutor.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation arriving during successful pending cleanup is sampled afterward`() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val uri = mock(Uri::class.java)
            val pending = FakePendingMediaStore(
                uri = uri,
                commitResult = 0,
                deleteBlock = {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    1
                },
            )
            val privateFile = File(root, "cancel-during-cleanup.mp4").apply { writeBytes(byteArrayOf(1)) }
            val publisher = MediaStorePublisher(
                pending,
                ioDispatcher,
                kotlinx.coroutines.Dispatchers.Unconfined,
            ) { "cancel-during-cleanup.mp4" }
            val cancellation = CancellationException("during-cleanup")

            supervisorScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.publish(
                        privateFile,
                        beforeCommit = { assertTrue(privateFile.delete()) },
                        onCommitted = {},
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                result.cancel(cancellation)
                release.countDown()
                assertSameCancellation(cancellation) { result.await() }
            }

            assertEquals(1, pending.deleteCalls)
        } finally {
            ioDispatcher.close()
            ioExecutor.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onCommitted is synchronous on the injected main dispatcher`() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "receipt-main") }
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val uri = mock(Uri::class.java)
            val pending = FakePendingMediaStore(uri)
            val privateFile = File(root, "callback-block.mp4").apply { writeBytes(byteArrayOf(1)) }
            val publisher = MediaStorePublisher(pending, ioDispatcher, mainDispatcher) { "callback-block.mp4" }

            supervisorScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.publish(
                        privateFile,
                        beforeCommit = { assertTrue(privateFile.delete()) },
                        onCommitted = {
                            assertTrue(Thread.currentThread().name.startsWith("receipt-main"))
                            entered.countDown()
                            assertTrue(release.await(10, TimeUnit.SECONDS))
                        },
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse(result.isCompleted)
                release.countDown()
                assertSame(uri, result.await().uri)
            }
        } finally {
            ioDispatcher.close()
            mainDispatcher.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    private fun publisher(pending: PendingMediaStore) = MediaStorePublisher(
        pendingMediaStore = pending,
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        displayName = { "test.mp4" },
    )

    private fun failingOutput(failure: Exception) = object : OutputStream() {
        override fun write(value: Int) = throw failure
        override fun write(bytes: ByteArray, offset: Int, length: Int) = throw failure
    }

    private fun closingOutput(failure: Exception) = object : ByteArrayOutputStream() {
        override fun close() = throw failure
    }

    private suspend fun assertPipelineFailure(
        phase: RunPhase,
        code: RunFailureCode,
        block: suspend () -> Unit,
    ): PipelineException {
        try {
            block()
            fail("Expected $phase/$code")
        } catch (failure: PipelineException) {
            assertEquals(phase, failure.phase)
            assertEquals(code, failure.code)
            return failure
        }
        error("unreachable")
    }

    private suspend fun assertSameCancellation(expected: CancellationException, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(generateSequence(actual as Throwable) { it.cause }.any { it === expected })
        }
    }

    private inner class CoordinatorFixture(
        inspection: OutputInspection = OutputInspection(true, true, true, false),
        renderFailure: Exception? = null,
        inspectionFailure: Exception? = null,
        inspectionAction: suspend () -> Unit = {},
        deletePrivate: Boolean = true,
    ) {
        val output = File(root, "output-${System.nanoTime()}.mp4")
        val publisher = FakePublisher(mock(Uri::class.java))
        var savingCalls = 0
        var committedCalls = 0
        private val store = object : PrivateOutputStore {
            override fun create(): File = output

            override fun deleteExact(file: File): Boolean {
                assertSame(output, file)
                return if (deletePrivate) !file.exists() || file.delete() else false
            }
        }
        val coordinator = ExportCoordinator(
            renderer = object : Renderer {
                override suspend fun render(spec: RenderSpec, output: File): ExportResult {
                    output.writeBytes(byteArrayOf(1))
                    renderFailure?.let { throw it }
                    return ExportResult.Builder().build()
                }
            },
            inspector = OutputInspector {
                inspectionAction()
                inspectionFailure?.let { throw it }
                inspection
            },
            publisher = publisher,
            privateOutputStore = store,
            onSaving = { savingCalls += 1 },
            onCommitted = { committedCalls += 1 },
        )
    }

    private class FakePublisher(uri: Uri) : Publisher {
        val receipt = PublicationReceipt(uri)
        var calls = 0

        override suspend fun publish(
            privateFile: File,
            beforeCommit: () -> Unit,
            onCommitted: (PublicationReceipt) -> Unit,
        ): PublicationReceipt {
            calls += 1
            beforeCommit()
            onCommitted(receipt)
            return receipt
        }
    }

    private class FakePendingMediaStore(
        private val uri: Uri,
        private val insertResult: Uri? = uri,
        private val insertFailure: Exception? = null,
        private val openReturnsNull: Boolean = false,
        private val openFailure: Exception? = null,
        private val commitResult: Int = 1,
        private val commitFailure: Exception? = null,
        private val deleteResult: Int = 1,
        private val deleteFailure: Exception? = null,
        private val deleteBlock: (() -> Int)? = null,
        output: OutputStream? = null,
        private val commitBlock: (() -> Int)? = null,
        private val events: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    ) : PendingMediaStore {
        val touchedUris = mutableListOf<Uri>()
        val written = ByteArrayOutputStream()
        private val output = output ?: object : OutputStream() {
            override fun write(value: Int) = written.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = written.write(bytes, offset, length)
            override fun close() {
                events += "closeOutput"
            }
        }
        var commitCalls = 0
        var deleteCalls = 0
        var deletedUri: Uri? = null

        override fun insertPending(displayName: String): Uri? {
            events += "insert"
            insertFailure?.let { throw it }
            return insertResult
        }

        override fun openOutput(uri: Uri): OutputStream? {
            touchedUris += uri
            events += "open"
            openFailure?.let { throw it }
            if (openReturnsNull) return null
            return output
        }

        override fun commit(uri: Uri): Int {
            touchedUris += uri
            commitCalls += 1
            events += "commit"
            commitFailure?.let { throw it }
            return commitBlock?.invoke() ?: commitResult
        }

        override fun deleteExact(uri: Uri): Int {
            touchedUris += uri
            deleteCalls += 1
            deletedUri = uri
            events += "deletePending"
            deleteFailure?.let { throw it }
            return deleteBlock?.invoke() ?: deleteResult
        }

    }
}
