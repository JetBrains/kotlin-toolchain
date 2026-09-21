/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.name

private val androidPackageExtensions = setOf("apk", "aab")

private val abiCheckLogger = LoggerFactory.getLogger("android-native-lib-abis")

/**
 * Reports the ABIs of the given [artifacts] that don't carry all the native libraries that their sibling ABIs
 * carry, which would break the app at runtime on the devices selecting them.
 *
 * This always validates what is actually packaged, but its severity depends on whether the user selected the ABIs
 * via `settings.android.abiFilters`:
 *  - when they didn't, an inconsistent package is never what they want, so this fails the build;
 *  - when they did, they may knowingly ship an ABI without some optional native library (one whose absence their
 *    code handles at runtime), which only they can know, so this merely warns.
 *
 * Note that selecting ABIs is not a claim about the consistency of the native libraries behind them, which is why
 * the explicit case still reports: a dependency added later can make a previously fine selection incomplete.
 */
internal fun checkNativeLibAbiConsistency(module: AmperModule, fragments: List<Fragment>, artifacts: List<Path>) {
    val abisSelectedExplicitly = fragments.any { it.settings.android.abiFilters.isNotEmpty() }

    for (artifact in artifacts.filter { it.extension.lowercase() in androidPackageExtensions }) {
        val nativeLibsByAbi = readNativeLibsByAbi(artifact)
        val incompleteAbis = findIncompleteAbis(nativeLibsByAbi)
        if (incompleteAbis.isEmpty()) continue

        val message = incompleteAbisMessage(
            module = module,
            artifact = artifact,
            packagedAbis = nativeLibsByAbi.keys,
            incompleteAbis = incompleteAbis,
            abisSelectedExplicitly = abisSelectedExplicitly,
        )
        if (abisSelectedExplicitly) abiCheckLogger.warn(message) else userReadableError(message)
    }
}

/**
 * An ABI that doesn't carry all the native libraries that its sibling ABIs carry.
 *
 * @property abi the name of the ABI directory, such as `arm64-v8a`
 * @property missingLibraryNames the file names of the native libraries that are present for some other ABI, but
 *   not for this one
 */
internal data class IncompleteAbi(val abi: String, val missingLibraryNames: Set<String>)

/**
 * Finds the ABIs of [nativeLibsByAbi] that don't carry every native library present in at least one other ABI.
 *
 * Android selects a single ABI per installation and only uses the native libraries of that one ABI. An ABI
 * missing a library therefore makes the app fail at runtime (with `UnsatisfiedLinkError`) on every device that
 * selects it, even though the very same library is present for other ABIs.
 *
 * The returned list is sorted by ABI name and is empty when all ABIs carry the same set of libraries (which is
 * trivially the case when there are fewer than two ABIs).
 *
 * Note that when the ABIs carry disjoint sets of libraries, all of them are reported as incomplete: there is no
 * subset of ABIs that could be packaged together safely, and only the user can decide what to do about it.
 */
internal fun findIncompleteAbis(nativeLibsByAbi: Map<String, Set<String>>): List<IncompleteAbi> {
    val allLibraryNames = nativeLibsByAbi.values.flatten().toSet()
    return nativeLibsByAbi.keys
        .sorted()
        .map { abi -> IncompleteAbi(abi = abi, missingLibraryNames = allLibraryNames - nativeLibsByAbi.getValue(abi)) }
        .filter { it.missingLibraryNames.isNotEmpty() }
}

/**
 * Matches the archive entries of native libraries, capturing the ABI and the library file name.
 *
 * APKs store them under `lib/<abi>/`, while app bundles store them under `<module>/lib/<abi>/` (the module being
 * `base` for the only module that Kotlin Toolchain produces).
 *
 * Only `.so` files are matched. AGP also packages the `gdbserver` and `gdb.setup` debug helpers from `jniLibs`, but
 * those are never loaded with `System.loadLibrary`, so a missing one can't break the app at runtime.
 */
private val nativeLibEntryRegex = Regex("""(?:[^/]+/)?lib/([^/]+)/([^/]+\.so)""")

/**
 * Groups the native libraries found in the given archive [entryNames] by the ABI they are packaged for.
 */
internal fun nativeLibsByAbi(entryNames: Sequence<String>): Map<String, Set<String>> = entryNames
    .mapNotNull { nativeLibEntryRegex.matchEntire(it) }
    .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
    .mapValues { it.value.toSet() }

/**
 * Groups the native libraries packaged in the given APK or app bundle by the ABI they are packaged for.
 */
private fun readNativeLibsByAbi(archive: Path): Map<String, Set<String>> =
    ZipFile(archive.toFile()).use { zip -> nativeLibsByAbi(zip.entries().asSequence().map { it.name }) }

private fun incompleteAbisMessage(
    module: AmperModule,
    artifact: Path,
    packagedAbis: Set<String>,
    incompleteAbis: List<IncompleteAbi>,
    abisSelectedExplicitly: Boolean,
): String = buildString {
    appendLine("Incomplete native libraries in ${artifact.name} (module '${module.userReadableName}'):")
    for (incompleteAbi in incompleteAbis) {
        appendLine("  - ${incompleteAbi.abi} is missing ${incompleteAbi.missingLibraryNames.sorted().joinToString()}")
    }
    appendLine()
    appendLine(
        "Android only uses the native libraries of a single ABI, chosen when the app is installed, so the app " +
                "would fail at runtime with UnsatisfiedLinkError on every device that selects one of the ABIs above."
    )
    appendLine()
    val completeAbis = packagedAbis.minus(incompleteAbis.map { it.abi }.toSet()).sorted()
    if (completeAbis.isNotEmpty()) {
        appendLine("Package only the ABIs that all native libraries support:")
        appendLine()
        appendLine("    settings:")
        appendLine("      android:")
        appendLine("        abiFilters: [ ${completeAbis.joinToString()} ]")
    } else {
        appendLine(
            "No ABI carries all the native libraries, so there is no subset that can be packaged safely. Either " +
                    "add the missing libraries, or use settings.android.abiFilters to choose the ABIs to package."
        )
    }
    if (abisSelectedExplicitly) {
        appendLine()
        append(
            "This is only a warning because settings.android.abiFilters selects the ABIs explicitly. Ignore it " +
                    "only if your code handles the absence of the libraries listed above at runtime."
        )
    }
}
