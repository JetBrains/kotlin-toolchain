/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.concurrency

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.NonWritableChannelException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes the given [block] under a double lock:
 * * a non-reentrant coroutine [Mutex] based on the [lockFile]'s path, getting exclusive access inside the current JVM
 * * a FileChannel lock on the given [lockFile], getting exclusive access across all processes on the system
 *
 * Both locks are unlocked after the method returns.
 *
 * The [block]'s parameter is the [FileChannel] used to lock the given [lockFile], if further inspection of the file is
 * needed while under the lock. The lock can be read and/or written to depending on the given open [options].
 *
 * Note: Since the first lock is a non-reentrant coroutine Mutex, callers MUST NOT call `withDoubleLock` again from
 * inside the given [block], that would make the current coroutine hang.
 * If an [owner] object is given, the owner's identity is used to detect such issues and eagerly fail instead of
 * hanging.
 *
 * @throws OverlappingFileLockException If this JVM already holds a lock that overlaps the requested region
 * @throws NonWritableChannelException If this file was not opened for writing
 * @throws FileAlreadyExistsException If a file of that name already exists and the [StandardOpenOption.CREATE_NEW]
 *         option is specified and the file is being opened for writing
 * @throws IOException If some other I/O error occurs
 */
suspend fun <T> FileMutexGroup.withDoubleLock(
    lockFile: Path,
    owner: Any? = null,
    options: Array<out OpenOption> = arrayOf(
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
    ),
    block: suspend (lockFileChannel: FileChannel) -> T,
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
//         returnsResultOf(block)
    }
    // The first lock locks the stuff inside one JVM process
    return withLock(lockFile, owner) {
        // The second lock locks a flagFile across all processes on the system
        lockFile.withFileChannelLock(*options) {
            block(it)
        }
    }
}

@RequiresOptIn("This API has concurrency implications that may not be obvious. " +
        "Please make sure you fully understand the documentation before using it.")
annotation class DelicateConcurrentApi

/**
 * Gets a cached value (if not null) or computes it with the given [computeUnderLock] function under a double lock:
 * * a non-reentrant coroutine [Mutex] based on the [lockFile]'s path, getting exclusive access inside the current JVM
 * * a FileChannel lock on the given [lockFile], getting exclusive access across all processes on the system
 *
 * This is similar to [withDoubleLock], but can reduce lock contention in cases where the function is called many times,
 * if we can check safely whether the computed value is already available without locking.
 *
 * The [getCached] function is called at every step to return early in case the value is already available: before
 * both locks, after taking the first lock, and after taking the second lock. If [getCached] still returns null at that
 * last point, [computeUnderLock] is called to actually compute the value.
 *
 * **Important: this means that [getCached] is not protected by the locks, and must not access the lock file in any
 * way, nor the protected resource.** Accessing the lock file could yield [AccessDeniedException].
 *
 * The parameter passed to [computeUnderLock] is the [FileChannel] used to lock the given [lockFile].
 * It can be used to write to the lock file if needed, but note that the lock file cannot be accessed from [getCached].
 *
 * **Important: the [computeUnderLock] function must create the result atomically with respect to how [getCached] views
 * it.** For instance, if multiple files are created by [computeUnderLock] and [getCached] checks for their existence
 * one by one, it might cause consistency issues because [getCached] is not run under the lock.
 *
 * Note: Since the first lock is a non-reentrant coroutine Mutex, callers MUST NOT call [getOrComputeWithDoubleLock]
 * (or similar locking functions) again from inside the given [computeUnderLock] function - that would make the current
 * coroutine hang.
 *
 * @throws OverlappingFileLockException If this JVM already holds a lock that overlaps the requested region
 * @throws IOException If some other I/O error occurs
 */
@DelicateConcurrentApi
suspend fun <T> FileMutexGroup.getOrComputeWithDoubleLock(
    lockFile: Path,
    getCached: suspend () -> T?,
    computeUnderLock: suspend (lockFileChannel: FileChannel) -> T,
): T {
    contract {
        callsInPlace(getCached, InvocationKind.AT_LEAST_ONCE)
        callsInPlace(computeUnderLock, InvocationKind.AT_MOST_ONCE)
//         returnsResultOf(getCached)
//         returnsResultOf(computeUnderLock)
    }
    getCached()?.let { return it }

    // The first lock prevents concurrent access inside one JVM process
    return withLock(lockFile) {
        getCached()?.let { return it }

        // The second lock locks the file across all processes on the system
        lockFile.withFileChannelLock(StandardOpenOption.WRITE, StandardOpenOption.CREATE) { lockFileChannel ->
            getCached()?.let { return it }

            computeUnderLock(lockFileChannel)
        }
    }
}

/**
 * Executes the given [block] under a system-wide exclusive lock on the file at this [Path].
 *
 * The [block]'s parameter is the [FileChannel] used to lock this file, if further inspection of the file is needed
 * while under the lock. The lock can be read and/or written to depending on the given open [options].
 *
 * If a [FileChannel] cannot be opened because of access errors, the open operation is retried several times.
 * This is to remediate the fact that sometimes the path stays inaccessible for a short time after being removed.
 *
 * This function suspends until the file can be locked or the current coroutine is canceled, whichever comes first.
 * There is no time limit: waiting for a lock is never considered an error, no matter how long the current holder
 * keeps it.
 *
 * If the given [options] contain [StandardOpenOption.CREATE] or [StandardOpenOption.CREATE_NEW], this function
 * guarantees that the file is present when locked. It is safe to delete the file concurrently in this case: this
 * function will recreate the file if it was deleted before the lock could be acquired.
 *
 * File locks are held on behalf of the entire Java virtual machine. They are not suitable for controlling access to a
 * file by multiple threads within the same virtual machine.
 *
 * @throws OverlappingFileLockException If a lock that overlaps the requested region is already held by this Java
 *         virtual machine, or if another thread is already blocked in this function and is attempting to lock an
 *         overlapping region of the same file
 * @throws NonWritableChannelException If this file was not opened for writing
 * @throws FileAlreadyExistsException If a file of that name already exists and the [StandardOpenOption.CREATE_NEW]
 *         option is specified and the file is being opened for writing
 * @throws IOException If some other I/O error occurs
 */
private suspend inline fun <T> Path.withFileChannelLock(vararg options: OpenOption, block: (FileChannel) -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
//         returnsResultOf(block)
    }
    while (true) {
        // Paths are sometimes still reserved for a short period after a file was deleted, which gives an
        // AccessDeniedException when trying to create a new file at this path. This is why we retry.
        // If it's a real permission issue, we'll rethrow the exception after a few attempts so the caller can handle it.
        val lockFileChannel = withRetry(retryOnException = { it is AccessDeniedException }) {
            FileChannel.open(this, *options)
        }
        lockFileChannel.use { fileChannel ->
            val fileLock = try {
                fileChannel.acquireExclusiveLock()
            } catch (e: NoSuchFileException) {
                val fileCreatedByOpen = options.any {
                    it == StandardOpenOption.CREATE || it == StandardOpenOption.CREATE_NEW
                }
                if (fileCreatedByOpen) {
                    // With the current open options, the file should be created by FileChannel.open().
                    // In this case, NoSuchFileException means the file was deleted between the channel opening and the
                    // locking attempt. We should re-open the channel (to re-create the file) and try locking again.
                    // This is consistent with the behavior we would get if the deletion happened just before open() or
                    // just after acquireExclusiveLock() (instead of between them).
                    yield() // cooperate with other coroutines before retrying
                    return@use // TODO use 'continue' when moving to Kotlin 2.2
                } else {
                    // With the current open options, the file isn't automatically created by the FileChannel.open().
                    // In this case, NoSuchFileException means the caller made a mistake, and we should let it bubble up.
                    throw e
                }
            }

            return fileLock.use {
                block(fileChannel)
            }
        }
    }
}

/**
 * Acquires an exclusive lock on this channel's file, polling with [FileChannel.tryLock] until the lock is available.
 * See the "Why poll instead of waiting?" section below for the reason why we don't simply use [FileChannel.lock].
 *
 * This function suspends until the file can be locked or the current coroutine is canceled, whichever comes first.
 *
 * Every new attempt is made after a longer delay than the previous one, up to [MAX_LOCK_POLL_INTERVAL]. Failures of
 * the lock attempt itself are not retried: they are thrown as-is, because some of them are recoverable in a way that
 * only the caller knows about (a deleted lock file, for instance).
 *
 * File locks are held on behalf of the entire Java virtual machine. They are not suitable for controlling access to a
 * file by multiple threads within the same virtual machine.
 *
 * ### Why poll instead of waiting?
 *
 * [FileChannel.lock] waits for the lock in the OS itself, which detects deadlocks and reports them as the
 * "Resource deadlock avoided" error. The problem is that this detection is at the level of processes and doesn't know
 * about threads, so 2 processes that each hold a lock in one thread while waiting for another lock in a different
 * thread look like a deadlock to the OS, even when there is none:
 * * Process 1, thread A, acquires lock on file A.
 * * Process 2, thread B, acquires lock on file B.
 * * Process 1, thread B, tries to lock file B and waits.
 * * Process 2, thread A, tries to lock file A and fails with the exception "Resource deadlock avoided".
 *
 * Nothing tells such a spurious report apart from a real deadlock, and it keeps coming back for as long as the
 * situation lasts, so waiting through it means calling [FileChannel.lock] in a loop: polling anyway, with an error to
 * interpret on every iteration.
 *
 * [FileChannel.tryLock] doesn't wait in the OS, so the OS has no wait graph to inspect and never reports such
 * deadlocks. Polling it gives a wait that we control, and that ends only when the lock is acquired or the caller is
 * canceled. The price is a polling delay instead of being woken up by the OS, and the fact that a real deadlock is no
 * longer detected: it results in an indefinite wait instead of an error.
 *
 * @throws ClosedChannelException If this channel is closed
 * @throws AsynchronousCloseException If another thread closes this channel while the invoking thread is blocked in
 *         this function
 * @throws OverlappingFileLockException If a lock that overlaps the requested region is already held by this Java
 *         virtual machine, or if another thread is already blocked in this function and is attempting to lock an
 *         overlapping region of the same file
 * @throws NonWritableChannelException If this channel was not opened for writing
 * @throws IOException If some other I/O error occurs
 */
private suspend fun FileChannel.acquireExclusiveLock(): FileLock {
    var pollInterval = INITIAL_LOCK_POLL_INTERVAL
    while (true) {
        tryLock()?.let { return it }
        // the jitter avoids convoys of processes polling in lockstep
        delay(pollInterval * Random.nextDouble(0.5, 1.0))
        pollInterval = minOf(pollInterval * 2, MAX_LOCK_POLL_INTERVAL)
    }
}

private val INITIAL_LOCK_POLL_INTERVAL = 5.milliseconds
private val MAX_LOCK_POLL_INTERVAL = 200.milliseconds

// todo (AB): 10 seconds is not enough
suspend fun <T> withRetry(
    retryCount: Int = 50,
    retryInterval: Duration = 200.milliseconds,
    retryOnException: (e: Exception) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    contract {
        callsInPlace(retryOnException, InvocationKind.UNKNOWN)
        callsInPlace(block, InvocationKind.AT_LEAST_ONCE)
//         returnsResultOf(block)
    }
    var attempt = 0
    var firstException: Exception? = null
    do {
        if (attempt > 0) delay(retryInterval)
        try {
            return block(attempt)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (!retryOnException(e)) {
                throw e
            }
            if (firstException == null) {
                firstException = e
            } else {
                firstException.addSuppressed(e)
            }
        }
        attempt++
    } while (attempt < retryCount)

    throw firstException
}
