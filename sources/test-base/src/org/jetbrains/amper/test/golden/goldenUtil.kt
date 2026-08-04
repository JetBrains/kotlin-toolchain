/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import java.nio.file.Path
import kotlin.io.path.exists

fun String.trimTrailingWhitespacesAndEmptyLines(): String {
    return lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString(separator = "\n") { it.trimEnd() }
}

/**
 * Resolves the given file name inside the [Path].
 * Tries to find a platform-specific variant of a golden file nearby
 * (a file with the same name, but with an additional platform-specific suffix at the end).
 *
 * Variants are looked up from the most specific one to the least specific one:
 * 1. OS- and architecture-specific, e.g. `myGoldenFile-mac-arm64.txt`
 * 2. OS-specific, e.g. `myGoldenFile-mac.txt`
 * 3. OS-agnostic, i.e., the given [goldenFileBaseName] as is
 *
 * This way, an architecture-specific variant only has to be added for those OSes where the expected result
 * actually differs between architectures. In that case, an explicit variant should be added for every architecture
 * of that OS (e.g. both `-mac-x64` and `-mac-arm64`), so that no architecture implicitly relies on a golden file
 * that only matches another one.
 *
 * @return the most specific golden file path that exists or the original one if no variant is found.
 */
fun Path.goldenFileOsArchAware(goldenFileBaseName: String): Path =
    goldenFileOsArchAware(goldenFileBaseName, OsFamily.current, Arch.current)

/**
 * Implementation of [goldenFileOsArchAware] with an explicit [osFamily] and [arch], so that the lookup can be tested
 * for platforms other than the current host.
 */
internal fun Path.goldenFileOsArchAware(goldenFileBaseName: String, osFamily: OsFamily, arch: Arch): Path {
    val osSuffix = when {
        osFamily.isWindows -> "-windows"
        osFamily.isMac -> "-mac"
        osFamily.isLinux -> "-linux"
        // the OS is not one of those we generate golden files for, there is nothing more specific to look for
        else -> return resolve(goldenFileBaseName)
    }

    return sequenceOf("$osSuffix-${arch.displayName}", osSuffix)
        .map { resolve(goldenFileBaseName.withNameSuffix(it)) }
        .firstOrNull { it.exists() }
        ?: resolve(goldenFileBaseName)
}

/**
 * Inserts the given [suffix] between the name of this file name and its extension.
 * E.g. `myGoldenFile.tree.txt` becomes `myGoldenFile.tree-mac.txt` with the suffix `-mac`.
 */
private fun String.withNameSuffix(suffix: String): String =
    substringBeforeLast(".") + suffix + "." + substringAfterLast(".")
