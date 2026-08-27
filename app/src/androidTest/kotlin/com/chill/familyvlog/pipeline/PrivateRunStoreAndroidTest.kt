package com.chill.familyvlog.pipeline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateRunStoreAndroidTest {
    @Test
    fun productRootIsInsideNoBackupFilesDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedRoot = context.noBackupFilesDir.toPath().resolve("vlog-runs").toAbsolutePath().normalize()
        val rootExisted = Files.exists(expectedRoot)
        val result = PrivateRunStore(context).saveFinalJson("{\"videos\":[{}]}", "{\"clips\":[{}]}")

        try {
            assertEquals(expectedRoot, result.toPath().parent.toAbsolutePath().normalize())
            assertTrue(result.toPath().toAbsolutePath().normalize().startsWith(context.noBackupFilesDir.toPath().toAbsolutePath().normalize()))
            assertTrue(Files.size(result.toPath().resolve("video_understanding.json")) > 0)
            assertTrue(Files.size(result.toPath().resolve("edit_plan.json")) > 0)
        } finally {
            deleteRun(result.toPath())
            if (!rootExisted) Files.deleteIfExists(expectedRoot)
        }
    }

    @Test
    fun realFileSystemAtomicallyMovesOneDirectoryWithBothClosedFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = context.noBackupFilesDir.toPath().resolve("atomic-directory-test-${UUID.randomUUID()}")
        val source = testRoot.resolve("source")
        val target = testRoot.resolve("target")
        val understanding = "设备理解 JSON"
        val plan = "设备剪辑 JSON"

        try {
            Files.createDirectories(source)
            writeUtf8(source.resolve("video_understanding.json"), understanding)
            writeUtf8(source.resolve("edit_plan.json"), plan)
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                fail("PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED")
            }

            assertFalse(Files.exists(source))
            assertTrue(Files.exists(target.resolve("video_understanding.json")))
            assertTrue(Files.exists(target.resolve("edit_plan.json")))
            assertEquals(understanding, readUtf8(target.resolve("video_understanding.json")))
            assertEquals(plan, readUtf8(target.resolve("edit_plan.json")))
        } finally {
            deleteRun(source)
            deleteRun(target)
            Files.deleteIfExists(testRoot)
        }
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { writer -> writer.write(content) }
    }

    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun deleteRun(directory: Path) {
        Files.deleteIfExists(directory.resolve("video_understanding.json"))
        Files.deleteIfExists(directory.resolve("edit_plan.json"))
        Files.deleteIfExists(directory)
    }
}
