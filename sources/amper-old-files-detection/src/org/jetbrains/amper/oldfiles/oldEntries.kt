/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.oldfiles

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.visitFileTree
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * An entry (a file or a directory) that is old, and can be deleted to free up space.
 */
data class OldEntry(
    val path: Path,

    /**
     * The time that defines the age of this entry. Depends on [OldIndicator] used for computing the age.
     */
    val timestamp: Instant,

    /**
     * The total size of the entry in bytes, including the contents, if it's a directory.
     */
    val sizeBytes: Long,
)

/**
 * The logic used to determine the age of an entry.
 */
enum class OldIndicator {
    /**
     * Use the time of creation of the entry.
     */
    Created,

    /**
     * Use maximum of last access and last modification times.
     */
    LastUsed,
}

/**
 * Returns the direct children of [Path] that is considered old at the instant of [cutoff].
 *
 * The definition of "old" depends on [indicator] (see the [OldIndicator] docs).
 * For every entry the age is computed over the entry itself and all of its contents (if it's a directory).
 *
 * If [Path] doesn't exist, can't be inspected (e.g. due to permission problems), or nothing inside it is old, an empty
 * list is returned.
 *
 * Entries that can't be inspected (e.g. due to permission problems) are never returned, to be on the safe side.
 */
fun Path.listOldEntries(cutoff: Instant, indicator: OldIndicator = OldIndicator.LastUsed): List<OldEntry> {
    if (!isDirectory()) return []

    val entries = try {
        listDirectoryEntries()
    } catch (e: IOException) {
        logger.debug("Could not list the entries of {}", this, e)
        []
    }
    return entries.mapNotNull { it.toOldEntryOrNull(cutoff, indicator) }
}

private fun Path.toOldEntryOrNull(cutoff: Instant, entryTime: OldIndicator): OldEntry? {
    val (timestamp, sizeBytes) = computeStatsIfOld(cutoff, entryTime) ?: return null
    return OldEntry(path = this, timestamp = timestamp, sizeBytes = sizeBytes)
}

private data class EntryStats(
    val timestamp: Instant,
    val sizeBytes: Long,
)

/**
 * Returns the stats of this entry when it is older than [cutoff], or `null` when the entry is newer than [cutoff], or
 * when its stats can't be inspected (e.g. due to permission problems).
 *
 * The timestamp is computed using [indicator].
 *
 * @see OldIndicator
 */
private fun Path.computeStatsIfOld(cutoff: Instant, indicator: OldIndicator): EntryStats? {
    var timestamp = Instant.DISTANT_PAST
    var sizeBytes = 0L
    var traverseException: IOException? = null

    fun BasicFileAttributes.entryTime(): Instant = when (indicator) {
        OldIndicator.Created -> creationTime()
        OldIndicator.LastUsed -> maxOf(lastAccessTime(), lastModifiedTime())
    }.toInstant().toKotlinInstant()

    visitFileTree(followLinks = false) {
        onPreVisitDirectory { _, attrs ->
            timestamp = maxOf(timestamp, attrs.entryTime())
            // We don't have to traverse the file tree further as we know that we'll return `null` from the method and the total size is not needed.
            if (timestamp >= cutoff) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }
        onVisitFile { _, attrs ->
            timestamp = maxOf(timestamp, attrs.entryTime())
            sizeBytes += attrs.size()
            // We don't have to traverse the file tree further as we know that we'll return `null` from the method and the total size is not needed.
            if (timestamp >= cutoff) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }
        onVisitFileFailed { _, exception ->
            traverseException = exception
            FileVisitResult.TERMINATE
        }
        onPostVisitDirectory { _, exception ->
            if (exception != null) {
                traverseException = exception
                FileVisitResult.TERMINATE
            } else {
                FileVisitResult.CONTINUE
            }
        }
    }

    if (traverseException != null) {
        logger.debug("Could not inspect the usage of {}", this, traverseException)
        return null
    }
    if (timestamp >= cutoff) return null
    return EntryStats(timestamp = timestamp, sizeBytes = sizeBytes)
}

private val logger = LoggerFactory.getLogger("oldEntries")
