/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.native

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.mavencentral.MavenCentralDefaultConfiguration
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo

private val MAVEN_CENTRAL_REPOSITORY_URL = MavenCentralDefaultConfiguration.url
private const val KOTLIN_BOOTSTRAP_REPOSITORY_URL = "https://packages.jetbrains.team/maven/p/kt/bootstrap"
private const val KOTLIN_GROUP_ID = "org.jetbrains.kotlin"

/**
 * Downloads and extracts current system specific kotlin native.
 * Returns null if kotlin native is not supported on current system/arch.
 */
context(_: OperationEventSink)
suspend fun Downloader.downloadAndExtractKotlinNative(
    version: String,
    userCacheRoot: AmperUserCacheRoot,
    systemInfo: SystemInfo = SystemInfo.CurrentHost,
): KonanDistribution? {
    val classifier = kotlinNativeClassifierFor(systemInfo) ?: return null
    val packaging = kotlinNativePackagingExtensionFor(systemInfo)

    val artifactUri = getUriForMavenArtifact(
        // Repositories are an implementation detail, we support resolving any version of the native distribution.
        // Dev versions are important for compiler plugin authors who want to test against new versions early.
        mavenRepository = if ("-dev-" in version) KOTLIN_BOOTSTRAP_REPOSITORY_URL else MAVEN_CENTRAL_REPOSITORY_URL,
        groupId = KOTLIN_GROUP_ID,
        artifactId = "kotlin-native-prebuilt",
        version = version,
        classifier = classifier,
        packaging = packaging,
    )
    val downloadedArchive = operationEventScope("downloading Kotlin Native compiler $version") {
        downloadFileToCacheLocation(artifactUri.toString(), userCacheRoot = userCacheRoot)
    }
    val nativeDistHome = operationEventScope("extracting Kotlin Native compiler $version") {
        extractFileToCacheLocation(downloadedArchive, userCacheRoot, ExtractOptions.STRIP_ROOT)
    }
    return KonanDistribution(
        homeDir = nativeDistHome,
        kotlinVersion = version,
    )
}

private fun kotlinNativePackagingExtensionFor(systemInfo: SystemInfo): String = when (systemInfo.family) {
    OsFamily.Windows -> "zip"
    OsFamily.Linux,
    OsFamily.MacOs,
    OsFamily.FreeBSD,
    OsFamily.Solaris,
        -> "tar.gz"
}

private fun kotlinNativeClassifierFor(systemInfo: SystemInfo): String? = when (systemInfo.family) {
    OsFamily.MacOs -> when (systemInfo.arch) {
        Arch.X64 -> "macos-x86_64"
        Arch.Arm64 -> "macos-aarch64"
    }
    OsFamily.Windows -> when (systemInfo.arch) {
        Arch.X64 -> "windows-x86_64"
        Arch.Arm64 -> null
    }
    OsFamily.Linux,
    OsFamily.FreeBSD,
    OsFamily.Solaris -> when (systemInfo.arch) {
        Arch.X64 -> "linux-x86_64"
        Arch.Arm64 -> null
    }
}
