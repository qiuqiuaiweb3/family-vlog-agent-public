package com.chill.familyvlog.pipeline

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CancellationException

internal fun interface AtomicDirectoryMover {
    fun move(source: Path, target: Path, vararg options: CopyOption): Path
}

class PrivateRunStore internal constructor(
    private val noBackupFilesDir: File,
    private val runId: () -> UUID,
    private val mover: AtomicDirectoryMover,
    private val fileIdentity: (Path) -> Any? = { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    },
) : RunStore {
    constructor(context: Context) : this(
        noBackupFilesDir = context.noBackupFilesDir,
        runId = UUID::randomUUID,
        mover = AtomicDirectoryMover { source, target, options -> Files.move(source, target, *options) },
    )

    override fun saveFinalJson(understandingJson: String, editPlanJson: String): File {
        val uuid = runId()
        val root = noBackupFilesDir.toPath().resolve("vlog-runs")
        val pending = root.resolve(".pending-$uuid")
        val target = root.resolve("run-$uuid")
        var pendingCreated = false
        var pendingFileKey: Any? = null
        var moveAttempted = false
        try {
            ensureRealRoot(root)
            if (existsWithoutFollowingLinks(pending) || existsWithoutFollowingLinks(target)) {
                throw IllegalStateException()
            }

            Files.createDirectory(pending)
            pendingCreated = true
            pendingFileKey = fileIdentity(pending)
            writeUtf8(pending.resolve(UNDERSTANDING_FILE), understandingJson)
            writeUtf8(pending.resolve(EDIT_PLAN_FILE), editPlanJson)
            if (existsWithoutFollowingLinks(target)) throw IllegalStateException()
            moveAttempted = true
            mover.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
            return target.toFile()
        } catch (cancellation: CancellationException) {
            cleanup(root, pending, target, pendingCreated, pendingFileKey, moveAttempted)
            throw cancellation
        } catch (failure: AtomicMoveNotSupportedException) {
            cleanup(root, pending, target, pendingCreated, pendingFileKey, moveAttempted)
            throw RunStoreException(RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED)
        } catch (failure: Exception) {
            cleanup(root, pending, target, pendingCreated, pendingFileKey, moveAttempted)
            throw RunStoreException(RunFailureCode.PRIVATE_STORAGE_FAILED)
        }
    }

    private fun ensureRealRoot(root: Path) {
        if (!existsWithoutFollowingLinks(root)) {
            Files.createDirectory(root)
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
    }

    private fun existsWithoutFollowingLinks(path: Path): Boolean =
        Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun writeUtf8(path: Path, content: String) {
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { writer -> writer.write(content) }
    }

    private fun cleanup(
        root: Path,
        pending: Path,
        target: Path,
        pendingCreated: Boolean,
        pendingFileKey: Any?,
        moveAttempted: Boolean,
    ) {
        try {
            if (!pendingCreated || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return

            val pendingMissingAfterMove = moveAttempted && isMissingWithoutFollowingLinks(pending)
            if (Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
                deleteRunFilesAndDirectory(pending)
            }
            if (pendingMissingAfterMove && targetMatchesIdentity(target, pendingFileKey)) {
                deleteRunFilesAndDirectory(target)
            }
        } catch (_: Exception) {
            // 清理和身份探测只能尽力执行，不得替换原始失败或取消。
        }
    }

    private fun isMissingWithoutFollowingLinks(path: Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        false
    } catch (_: NoSuchFileException) {
        true
    } catch (_: Exception) {
        false
    }

    private fun targetMatchesIdentity(target: Path, pendingFileKey: Any?): Boolean {
        if (pendingFileKey == null || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            pendingFileKey == fileIdentity(target)
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteRunFilesAndDirectory(directory: Path) {
        tryDelete(directory.resolve(UNDERSTANDING_FILE))
        tryDelete(directory.resolve(EDIT_PLAN_FILE))
        tryDelete(directory)
    }

    private fun tryDelete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // 即使尽力清理失败，对外仍只暴露固定错误语义。
        }
    }

    private companion object {
        const val UNDERSTANDING_FILE = "video_understanding.json"
        const val EDIT_PLAN_FILE = "edit_plan.json"
    }
}
