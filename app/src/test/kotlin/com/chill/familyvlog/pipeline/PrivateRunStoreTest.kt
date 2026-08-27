package com.chill.familyvlog.pipeline

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivateRunStoreTest {
    @Test
    fun `moves one complete directory atomically after two exact utf8 files are closed`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("12345678-1234-5678-9abc-123456789abc")
        val understanding = "理解内容：家庭时刻"
        val plan = "剪辑计划：温馨"
        val mover = RecordingMover { source, target, options ->
            assertEquals(noBackup.resolve("vlog-runs/.pending-$uuid"), source)
            assertEquals(noBackup.resolve("vlog-runs/run-$uuid"), target)
            assertEquals(listOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE), options)
            assertEquals(
                setOf("video_understanding.json", "edit_plan.json"),
                Files.list(source).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() },
            )
            assertTrue(Files.size(source.resolve("video_understanding.json")) > 0)
            assertTrue(Files.size(source.resolve("edit_plan.json")) > 0)
            assertEquals(understanding, readUtf8(source.resolve("video_understanding.json")))
            assertEquals(plan, readUtf8(source.resolve("edit_plan.json")))
            Files.move(source, target, *options.toTypedArray())
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        val result = store.saveFinalJson(understanding, plan)
        val resultPath = noBackup.resolve("vlog-runs/run-$uuid")

        assertEquals(resultPath.toFile(), result)
        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertEquals(
            listOf("run-$uuid"),
            Files.list(noBackup.resolve("vlog-runs")).use { entries -> entries.map { it.fileName.toString() }.toList() },
        )
        assertEquals(
            setOf("video_understanding.json", "edit_plan.json"),
            Files.list(resultPath).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() },
        )
        assertEquals(understanding, readUtf8(resultPath.resolve("video_understanding.json")))
        assertEquals(plan, readUtf8(resultPath.resolve("edit_plan.json")))
    }

    @Test
    fun `ordinary move failure cleans only this run and uses no fallback`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("22345678-1234-5678-9abc-123456789abc")
        val root = Files.createDirectories(noBackup.resolve("vlog-runs"))
        val sibling = Files.createDirectories(root.resolve("run-existing"))
        writeUtf8(sibling.resolve("sentinel"), "keep")
        val mover = RecordingMover { _, _, _ -> throw IllegalStateException("filesystem details") }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        val failure = expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals("PRIVATE_STORAGE_FAILED", failure.message)
        assertEquals(1, mover.calls)
        assertFalse(Files.exists(root.resolve(".pending-$uuid")))
        assertFalse(Files.exists(root.resolve("run-$uuid")))
        assertEquals("keep", readUtf8(sibling.resolve("sentinel")))
    }

    @Test
    fun `unsupported atomic move has distinct code cleanup and no retry`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("32345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { source, target, _ ->
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `move cancellation cleans exact run and preserves the same instance`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("42345678-1234-5678-9abc-123456789abc")
        val cancellation = CancellationException("cancelled")
        val mover = RecordingMover { _, _, _ -> throw cancellation }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        try {
            store.saveFinalJson("understanding", "plan")
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `move then ordinary failure removes the final directory created by this run`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("43345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            throw IllegalStateException("failure after move")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `move then cancellation removes the final directory and preserves the same instance`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("44345678-1234-5678-9abc-123456789abc")
        val cancellation = CancellationException("cancelled after move")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            throw cancellation
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        try {
            store.saveFinalJson("understanding", "plan")
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `missing identities preserve replacement target and original ordinary failure mapping`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("45345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            replaceWithForeignDirectory(target)
            throw IllegalStateException("failure after replacement")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { path ->
            if (path.fileName.toString().startsWith(".pending-")) null else "foreign"
        }

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertForeignDirectory(noBackup.resolve("vlog-runs/run-$uuid"))
    }

    @Test
    fun `missing identities preserve replacement target and same cancellation instance`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("46345678-1234-5678-9abc-123456789abc")
        val cancellation = CancellationException("cancelled after replacement")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            replaceWithForeignDirectory(target)
            throw cancellation
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { path ->
            if (path.fileName.toString().startsWith(".pending-")) null else "foreign"
        }

        try {
            store.saveFinalJson("understanding", "plan")
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertForeignDirectory(noBackup.resolve("vlog-runs/run-$uuid"))
    }

    @Test
    fun `different nonnull identity preserves replacement target after move failure`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("49345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            replaceWithForeignDirectory(target)
            throw IllegalStateException("failure after foreign replacement")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { path ->
            if (path.fileName.toString().startsWith(".pending-")) "owned" else "foreign"
        }

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertForeignDirectory(noBackup.resolve("vlog-runs/run-$uuid"))
    }

    @Test
    fun `pending presence blocks cleanup of same identity foreign target`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("50345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { _, target, _ ->
            Files.createDirectory(target)
            writeUtf8(target.resolve("video_understanding.json"), "foreign understanding")
            writeUtf8(target.resolve("edit_plan.json"), "foreign plan")
            writeUtf8(target.resolve("sentinel"), "keep")
            throw IllegalStateException("failure without moving pending")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { "same-identity" }

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals(1, mover.calls)
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertForeignDirectory(noBackup.resolve("vlog-runs/run-$uuid"))
    }

    @Test
    fun `identity read failure cannot replace atomic move unsupported code`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("47345678-1234-5678-9abc-123456789abc")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "after move")
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { path ->
            if (path.fileName.toString().startsWith(".pending-")) "owned" else throw IOException("identity unavailable")
        }

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertTrue(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `identity read failure cannot replace same cancellation instance`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("48345678-1234-5678-9abc-123456789abc")
        val cancellation = CancellationException("cancelled before identity cleanup")
        val mover = RecordingMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
            throw cancellation
        }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover) { path ->
            if (path.fileName.toString().startsWith(".pending-")) "owned" else throw IOException("identity unavailable")
        }

        try {
            store.saveFinalJson("understanding", "plan")
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertTrue(Files.exists(noBackup.resolve("vlog-runs/run-$uuid")))
    }

    @Test
    fun `root write failure maps privately without touching adjacent data`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("52345678-1234-5678-9abc-123456789abc")
        val blockingRoot = writeUtf8(noBackup.resolve("vlog-runs"), "not a directory")
        val mover = RecordingMover { source, target, options -> Files.move(source, target, *options.toTypedArray()) }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("understanding", "plan")
        }

        assertEquals("not a directory", readUtf8(blockingRoot))
        assertEquals(0, mover.calls)
    }

    @Test
    fun `preexisting final directory fails closed without overwrite deletion or move`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("62345678-1234-5678-9abc-123456789abc")
        val target = Files.createDirectories(noBackup.resolve("vlog-runs/run-$uuid"))
        val sentinel = writeUtf8(target.resolve("existing.json"), "original")
        val mover = RecordingMover { source, destination, options -> Files.move(source, destination, *options.toTypedArray()) }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)

        expectStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED) {
            store.saveFinalJson("new understanding", "new plan")
        }

        assertEquals(0, mover.calls)
        assertEquals("original", readUtf8(sentinel))
        assertFalse(Files.exists(noBackup.resolve("vlog-runs/.pending-$uuid")))
        assertEquals(listOf("existing.json"), Files.list(target).use { entries -> entries.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `symbolic link run root fails closed without writing outside no backup directory`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("72345678-1234-5678-9abc-123456789abc")
        val external = Files.createTempDirectory("private-run-store-external-")
        val root = noBackup.resolve("vlog-runs")
        Files.createSymbolicLink(root, external)
        val mover = RecordingMover { source, target, options -> Files.move(source, target, *options.toTypedArray()) }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)
        var failure: RunStoreException? = null

        try {
            try {
                store.saveFinalJson("private understanding", "private plan")
            } catch (actual: RunStoreException) {
                failure = actual
            }

            assertEquals(0L, Files.list(external).use { entries -> entries.count() })
            assertTrue(Files.isSymbolicLink(root))
            assertEquals(external, Files.readSymbolicLink(root))
            assertEquals(0, mover.calls)
            assertStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED, failure)
        } finally {
            Files.deleteIfExists(root)
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preexisting dangling pending link fails closed without deleting the link`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("82345678-1234-5678-9abc-123456789abc")
        val root = Files.createDirectory(noBackup.resolve("vlog-runs"))
        val pending = root.resolve(".pending-$uuid")
        val missingDestination = noBackup.resolve("missing-pending-destination")
        Files.createSymbolicLink(pending, missingDestination)
        val mover = RecordingMover { source, target, options -> Files.move(source, target, *options.toTypedArray()) }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)
        var failure: RunStoreException? = null

        try {
            try {
                store.saveFinalJson("private understanding", "private plan")
            } catch (actual: RunStoreException) {
                failure = actual
            }

            assertTrue(Files.isSymbolicLink(pending))
            assertEquals(missingDestination, Files.readSymbolicLink(pending))
            assertFalse(Files.exists(missingDestination))
            assertEquals(0, mover.calls)
            assertStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED, failure)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    @Test
    fun `preexisting dangling final link fails closed without deleting the link or invoking mover`() = withTempDirectory { noBackup ->
        val uuid = UUID.fromString("92345678-1234-5678-9abc-123456789abc")
        val root = Files.createDirectory(noBackup.resolve("vlog-runs"))
        val target = root.resolve("run-$uuid")
        val missingDestination = noBackup.resolve("missing-final-destination")
        Files.createSymbolicLink(target, missingDestination)
        val mover = RecordingMover { _, _, _ -> throw IllegalStateException("mover must not run") }
        val store = PrivateRunStore(noBackup.toFile(), { uuid }, mover)
        var failure: RunStoreException? = null

        try {
            try {
                store.saveFinalJson("private understanding", "private plan")
            } catch (actual: RunStoreException) {
                failure = actual
            }

            assertTrue(Files.isSymbolicLink(target))
            assertEquals(missingDestination, Files.readSymbolicLink(target))
            assertFalse(Files.exists(missingDestination))
            assertEquals(0, mover.calls)
            assertStoreFailure(RunFailureCode.PRIVATE_STORAGE_FAILED, failure)
        } finally {
            Files.deleteIfExists(target)
        }
    }

    private class RecordingMover(
        private val behavior: (Path, Path, List<CopyOption>) -> Path,
    ) : AtomicDirectoryMover {
        var calls = 0

        override fun move(source: Path, target: Path, vararg options: CopyOption): Path {
            calls += 1
            return behavior(source, target, options.toList())
        }
    }

    private fun expectStoreFailure(code: RunFailureCode, block: () -> Unit): RunStoreException = try {
        block()
        throw AssertionError("expected $code")
    } catch (failure: RunStoreException) {
        assertEquals(code, failure.code)
        assertEquals(code.name, failure.message)
        assertEquals(null, failure.cause)
        failure
    }

    private fun assertStoreFailure(code: RunFailureCode, failure: RunStoreException?) {
        assertTrue("expected $code", failure is RunStoreException)
        assertEquals(code, failure?.code)
        assertEquals(code.name, failure?.message)
        assertEquals(null, failure?.cause)
    }

    private fun replaceWithForeignDirectory(target: Path) {
        Files.delete(target.resolve("video_understanding.json"))
        Files.delete(target.resolve("edit_plan.json"))
        Files.delete(target)
        Files.createDirectory(target)
        writeUtf8(target.resolve("video_understanding.json"), "foreign understanding")
        writeUtf8(target.resolve("edit_plan.json"), "foreign plan")
        writeUtf8(target.resolve("sentinel"), "keep")
    }

    private fun assertForeignDirectory(target: Path) {
        assertEquals("foreign understanding", readUtf8(target.resolve("video_understanding.json")))
        assertEquals("foreign plan", readUtf8(target.resolve("edit_plan.json")))
        assertEquals("keep", readUtf8(target.resolve("sentinel")))
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("private-run-store-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun writeUtf8(path: Path, content: String): Path = Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
}
