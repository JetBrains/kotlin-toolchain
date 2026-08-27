/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.io.path

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/**
 * Executes [block] with a new temp directory passed as argument, and then deletes the temp directory.
 *
 * This method has the same guarantees as [deleteRecursively]:
 *
 * If an exception occurs attempting to read, open or delete any entry under the given file tree, this method skips
 * that entry and continues. Such exceptions are collected and, after attempting to delete all entries, an
 * [IOException][java.io.IOException] is thrown containing those exceptions as suppressed exceptions.
 * Maximum of 64 exceptions are collected. After reaching that amount, thrown exceptions are ignored and not collected.
 */
fun <T> withTempDir(block: (tempDir: Path) -> T): T {
    val temp = createTempDirectory()
    try {
        return block(temp)
    } finally {
        temp.deleteRecursively()
    }
}
