/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.incrementalcache

import org.jetbrains.amper.stdlib.hashing.hash
import java.io.File
import kotlin.io.path.Path

/**
 * Computes the hash of all files in the current classpath.
 * This is a good way to identify the currently running code, and invalidate caches based on it.
 *
 * The hash algorithm used is specified by the [algorithm] parameter.
 *
 * @param algorithm The name of the hash algorithm to use.
 * @return The hash of the classpath.
 */
fun computeClassPathHash(algorithm: String = "md5"): String {
    val classPath = System.getProperty("java.class.path").ifEmpty { null } ?: return "empty"
    val classPathFiles = classPath.split(File.pathSeparator).map { Path(it) }
    return classPathFiles.hash(algorithm).toHexString()
}
