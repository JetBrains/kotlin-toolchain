/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.MetadataStorage
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.concurrency.FileMutexGroup
import org.jetbrains.amper.concurrency.withDoubleLock
import java.nio.file.Path

/**
 * A [MetadataStorage] that performs all operations accessing the metadata cache under a lock on the given [lockFile],
 * so that multiple processes can safely share the same cache.
 *
 * Locking at the file storage level would not be sufficient, because the metadata storage performs non-atomic
 * sequences of operations on the cache, such as checking the last modification time, downloading the new content,
 * and writing both the content and its modification time.
 */
internal class ProcessSafeMetadataStorage<T : Comparable<T>?> private constructor(
    private val lockFile: Path,
    private val delegate: MetadataStorage<T>,
) : MetadataStorage<T> by delegate {

    override suspend fun scheduleUpdate() {
        // The updates scheduled by the delegate would not be protected by the lock (not controlled by us)
        throw UnsupportedOperationException("Scheduled updates are not supported, call update() instead")
    }

    override suspend fun update(): Boolean = withCacheLock { delegate.update() }

    override suspend fun reload() = withCacheLock { delegate.reload() }

    private suspend fun <R> withCacheLock(block: suspend () -> R): R =
        FileMutexGroup.Default.withDoubleLock(lockFile) { block() }

    companion object {
        /**
         * Creates a [ProcessSafeMetadataStorage] delegating to the storage created by [createDelegate].
         *
         * The delegate is created under the lock, because metadata storages usually load (and sometimes write) the
         * cached metadata during their initialization.
         */
        suspend fun <T : Comparable<T>?> create(
            lockFile: Path,
            createDelegate: () -> MetadataStorage<T>,
        ): ProcessSafeMetadataStorage<T> = FileMutexGroup.Default.withDoubleLock(lockFile) {
            ProcessSafeMetadataStorage(lockFile, createDelegate())
        }
    }
}
