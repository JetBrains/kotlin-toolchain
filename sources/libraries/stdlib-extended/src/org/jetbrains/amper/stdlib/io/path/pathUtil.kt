/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.io.path

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Returns `true` if this [Path] exists and is an empty directory, or `false` in any other case.
 */
fun Path.isEmptyDirectory(): Boolean {
    return isDirectory() && useDirectoryEntries { it.none() }
}