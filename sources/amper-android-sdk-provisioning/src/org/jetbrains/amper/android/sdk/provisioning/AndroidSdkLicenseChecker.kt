/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

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
    private val packageLicenseReader: (Path) -> AndroidSdkPackageLicense,
) {
    private val normalizedAndroidSdkPath = androidSdkPath.toAbsolutePath().normalize()

    suspend fun check(): AndroidSdkLicenseCheckResult {
        val packageManifests = findAndroidSdkPackageManifests(normalizedAndroidSdkPath)
        val licenseFiles = findAndroidSdkLicenseFiles(normalizedAndroidSdkPath)
        val packagesByLicenseId = incrementalCache.executeForSerializable(
            key = "$androidSdkLicenseCheckCacheKeyPrefix:${normalizedAndroidSdkPath.pathString}",
            inputValues = emptyMap(),
            inputFiles = (packageManifests + licenseFiles).toList(),
        ) {
            packageManifests
                .map(packageLicenseReader)
                .filterNot { it.license.checkAccepted(normalizedAndroidSdkPath) }
                .groupBy { it.license.id }
                .toSortedMap()
                .mapValues { entry -> entry.value.map { it.packagePath }.distinct().sorted() }
        }
        return if (packagesByLicenseId.isEmpty()) {
            AndroidSdkLicenseCheckResult.Accepted
        } else {
            AndroidSdkLicenseCheckResult.Unaccepted(packagesByLicenseId)
        }
    }
}

sealed interface AndroidSdkLicenseCheckResult {
    data object Accepted : AndroidSdkLicenseCheckResult

    data class Unaccepted(
        val packagesByLicenseId: Map<String, List<String>>,
    ) : AndroidSdkLicenseCheckResult
}

internal data class AndroidSdkPackageLicense(
    val packagePath: String,
    val license: License,
)

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
