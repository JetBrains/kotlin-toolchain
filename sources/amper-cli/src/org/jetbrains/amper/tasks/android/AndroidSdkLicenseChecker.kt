/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import com.android.repository.api.License
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForSerializable
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.visitFileTree

private const val androidSdkLicenseCheckCacheKeyPrefix = "android-sdk-license-check"

internal class AndroidSdkLicenseChecker(
    androidSdkPath: Path,
    private val incrementalCache: IncrementalCache,
    private val packageLicenseReader: (Path) -> License,
) {
    private val normalizedAndroidSdkPath = androidSdkPath.toAbsolutePath().normalize()

    suspend fun findUnacceptedLicenseIds(): List<String> {
        val packageManifests = findAndroidSdkPackageManifests(normalizedAndroidSdkPath)
        val licenseFiles = findAndroidSdkLicenseFiles(normalizedAndroidSdkPath)
        return incrementalCache.executeForSerializable(
            key = "$androidSdkLicenseCheckCacheKeyPrefix:${normalizedAndroidSdkPath.pathString}",
            inputValues = emptyMap(),
            inputFiles = (packageManifests + licenseFiles).toList(),
        ) {
            packageManifests
                .map(packageLicenseReader)
                .filterNot { it.checkAccepted(normalizedAndroidSdkPath) }
                .map { it.id }
                .distinct()
                .sorted()
        }
    }

    /**
     * Writes the license hash files for the given ids into the SDK's licenses
     * directory — the same acceptance that `sdkmanager --licenses` performs.
     * Only the listed licenses are accepted, never anything else. Returns the
     * ids that were actually written (unknown ids are ignored).
     */
    fun acceptLicenses(ids: Set<String>): Set<String> {
        val licensesById = findAndroidSdkPackageManifests(normalizedAndroidSdkPath)
            .map(packageLicenseReader)
            .associateBy { it.id }
        val written = mutableSetOf<String>()
        for (id in ids) {
            val license = licensesById[id] ?: continue
            if (license.setAccepted(normalizedAndroidSdkPath)) {
                written += id
            }
        }
        return written
    }
}

internal fun findAndroidSdkPackageManifests(androidSdkPath: Path): Set<Path> = buildSet {
    androidSdkPath.visitFileTree {
        onPreVisitDirectory { directory, _ ->
            val packageManifest = directory / "package.xml"
            if (packageManifest.isRegularFile()) {
                add(packageManifest)
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }
        }
    }
}

private fun findAndroidSdkLicenseFiles(androidSdkPath: Path): Set<Path> {
    val licensesDirectory = androidSdkPath / License.LICENSE_DIR
    if (!licensesDirectory.isDirectory()) return emptySet()
    return licensesDirectory.listDirectoryEntries()
        .filterTo(mutableSetOf()) { it.isRegularFile() }
}
