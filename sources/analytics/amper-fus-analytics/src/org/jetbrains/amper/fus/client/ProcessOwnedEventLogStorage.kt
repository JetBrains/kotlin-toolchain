/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.FileStorage
import com.jetbrains.fus.reporting.jvm.JvmFileStorage
import org.jetbrains.amper.concurrency.FileMutexGroup
import org.jetbrains.amper.concurrency.withDoubleLock
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.uuid.Uuid

/**
 * A [FileStorage] for the FUS event log, backed by a directory that is owned by the current process.
 *
 * The persistent event queue of the FUS client assumes that it has exclusive access to its storage: it sends and
 * deletes all event log files except the one it is currently writing to. If several processes shared the same
 * directory, a process could send and delete the file that another process is still writing to, and two processes
 * could send the same file twice.
 *
 * To avoid this, each storage gets its own subdirectory of the queues root, and holds a lock on it until the storage
 * is closed. When a new storage is created, the files from the directories that are no longer owned (because their
 * process finished or crashed) are moved to the new directory, so that they are eventually sent.
 */
internal class ProcessOwnedEventLogStorage private constructor(
    ownedDir: Path,
    private val ownershipLockChannel: FileChannel,
) : FileStorage by JvmFileStorage(ownedDir) {

    override suspend fun close() {
        // closing the channel releases the ownership lock, so other processes can adopt the remaining files
        @Suppress("BlockingMethodInNonBlockingContext") // just noise because of the declared IOException
        ownershipLockChannel.close()
    }

    companion object {
        /**
         * Creates a new storage in a new subdirectory of the given [queuesRoot], and adopts the files of the
         * subdirectories that are not owned by a live storage anymore.
         */
        suspend fun create(queuesRoot: Path): ProcessOwnedEventLogStorage {
            queuesRoot.createDirectories()
            // Creating and adopting directories under the same global lock (over all queues) guarantees that we never
            // adopt a directory that is being created, and that 2 processes never adopt the same directory.
            return FileMutexGroup.Default.withDoubleLock(queuesRoot.siblingLockFile()) {
                val ownedDir = (queuesRoot / Uuid.random().toString()).createDirectories()
                val ownershipLockChannel = lockOwnership(ownedDir)
                adoptUnownedDirectories(queuesRoot, ownedDir)
                ProcessOwnedEventLogStorage(ownedDir, ownershipLockChannel)
            }
        }

        private fun lockOwnership(dir: Path): FileChannel {
            val channel = FileChannel.open(dir.siblingLockFile(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            try {
                checkNotNull(channel.tryLock()) { "The lock of a new event log directory should be free" }
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            return channel
        }

        private fun adoptUnownedDirectories(queuesRoot: Path, ownedDir: Path) {
            queuesRoot.listDirectoryEntries()
                .filter { it.isDirectory() && it != ownedDir }
                .forEach { adoptIfUnowned(dir = it, newOwnedDir = ownedDir) }
        }

        private fun adoptIfUnowned(dir: Path, newOwnedDir: Path) {
            val lockFile = dir.siblingLockFile()
            FileChannel.open(lockFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
                val lock = channel.tryLockOrNull() ?: return // the directory is owned by a live storage
                lock.use {
                    dir.listDirectoryEntries().forEach { it.moveTo(newOwnedDir / it.name) }
                    dir.deleteExisting()
                }
            }
            // Safe because we hold the queues root lock, so nobody else can try to lock this file at this point.
            // This must be done after closing the channel, otherwise it fails on Windows.
            lockFile.deleteIfExists()
        }

        private fun FileChannel.tryLockOrNull() = try {
            tryLock()
        } catch (_: OverlappingFileLockException) {
            // This means the lock is held by a storage in the current JVM, which shouldn't happen in our current
            // production usage, but could happen in the future if we created more storages, and we do in tests.
            // The behavior should be to consider this directory live, as if it were owned by another process, thus null.
            null
        }
    }
}

private fun Path.siblingLockFile(): Path = resolveSibling("$name.lock")
