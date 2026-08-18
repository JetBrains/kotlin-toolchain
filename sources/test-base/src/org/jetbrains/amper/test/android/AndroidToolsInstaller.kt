/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.android

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.JdkSelectionMode
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jdk.provisioning.JdkProvisioningCriteria
import org.jetbrains.amper.jdk.provisioning.orThrow
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.stdlib.io.path.clean
import java.nio.file.Path
import kotlin.io.path.appendLines
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readLines

internal object AndroidToolsInstaller {

    private data class License(val name: String, val hash: String)

    // from https://github.com/thyrlian/AndroidSDK/blob/master/android-sdk/license-accepter.sh
    private val licensesToAccept: List<License> = listOf(
        License(name = "android-googletv-license", hash = "601085b94cd77f0b54ff86406957099ebe79c4d6"),
        License(name = "android-sdk-license", hash = "8933bad161af4178b1185d1a37fbf41ea5269c55"),
        License(name = "android-sdk-license", hash = "d56f5187479451eabf01fb78af6dfcb131a6481e"),
        License(name = "android-sdk-license", hash = "24333f8a63b6825ea9c5514f83c2829b004d1fee"),
        License(name = "android-sdk-preview-license", hash = "84831b9409646a918e30573bab4c9c91346d8abd"),
        License(name = "android-sdk-preview-license", hash = "504667f4c0de7af1a06de9f4b1727b84351f2910"),
        License(name = "google-gdk-license", hash = "33b6a2b64607f11b759f320ef9dff4ae5c47d97a"),
        License(name = "intel-android-extra-license", hash = "d975f751698a77b662f1254ddbeed3901e976f5a"),
        License(name = "android-sdk-arm-dbt-license", hash = "859f317696f67ef3d7f30a50a5560e7834b43903"),
    )

    suspend fun prepare(androidSdkHome: Path, androidUserHomeParent: Path, androidSetupCacheDir: Path): AndroidTools {
        val incrementalCache = IncrementalCache(
            stateRoot = androidSetupCacheDir / "incremental.state",
            // The cache should be invalidated when the code that downloads the tools changes.
            // We don't need the full classpath hash here, because it would change each time we change a test.
            // This constant string is a good compromise, but we must remember to update it if we change the code.
            codeVersion = "android-sdk-5",
        )
        val result = incrementalCache.execute(
            key = "android-sdk",
            inputValues = mapOf(
                "androidSdkHomePath" to androidSdkHome.pathString,
                "licensesToAccept" to licensesToAccept.joinToString(" ") { "${it.name}-${it.hash}" },
            ),
            inputFiles = emptyList(),
        ) {
            androidSdkHome.clean()

            // we need a JDK to run the Java-based Android command line tools
            val jdk = getSomeJdk(androidSetupCacheDir, incrementalCache)

            licensesToAccept.forEach { (name, hash) ->
                acceptLicense(androidSdkHome, name, hash)
            }

            IncrementalCache.ExecutionResult(
                outputFiles = listOf(androidSdkHome / "licenses", jdk.homeDir),
            )
        }

        return AndroidTools(
            androidSdkHome = androidSdkHome,
            androidUserHomeParent = androidUserHomeParent,
            javaHome = result.outputFiles[1],
        )
    }

    private suspend fun getSomeJdk(
        androidSetupCacheDir: Path,
        incrementalCache: IncrementalCache,
    ): Jdk = context(NoopProblemReporter) {
        JdkProvider(AmperUserCacheRoot(androidSetupCacheDir), incrementalCache = incrementalCache)
            .getJdk(
                criteria = JdkProvisioningCriteria(majorVersion = DefaultVersions.jdk),
                selectionMode = JdkSelectionMode.auto,
            )
            .orThrow()
    }

    private fun acceptLicense(androidSdkHome: Path, name: String, hash: String) {
        val licenseFile = androidSdkHome / "licenses" / name
        licenseFile.parent.createDirectories()

        if (!licenseFile.exists()) {
            licenseFile.createFile()
        }
        if (hash !in licenseFile.readLines()) {
            licenseFile.appendLines(listOf(hash))
        }
    }
}
