/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import com.android.repository.api.ConsoleProgressIndicator
import com.android.repository.api.LocalPackage
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.Repository
import com.android.repository.impl.meta.LocalPackageImpl
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.sdklib.repository.AndroidSdkHandler
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import org.jetbrains.amper.concurrency.FileMutexGroup
import org.jetbrains.amper.concurrency.withDoubleLock
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import javax.xml.bind.JAXBElement
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream

private const val androidRepositoryUrl = "https://dl.google.com/"
private const val androidRepositoryBasePath = "/android/repository/"
private const val androidSystemImagePath = "/sys-img/google_apis/"

private val androidRepositoryUrlBuilder: URLBuilder
    get() = URLBuilder(androidRepositoryUrl)
        .also { it.appendPathSegments(androidRepositoryBasePath) }
private val androidSystemImagesRepositoryUrlBuilder: URLBuilder
    get() = URLBuilder(androidRepositoryUrl)
        .also { it.appendPathSegments(androidRepositoryBasePath) }
        .also { it.appendPathSegments(androidSystemImagePath) }

class SdkInstallManager(private val userCacheRoot: AmperUserCacheRoot, private val androidSdkPath: Path) {

    init {
        androidSdkPath.createDirectories()
    }

    suspend fun install(packagePath: String): RepoPackage {
        return if (packagePath.contains("system-images")) {
            installSystemImage(packagePath)
        } else {
            installPackage(packagePath)
        }
    }

    suspend fun installPackage(packagePath: String): RepoPackage =
        FileMutexGroup.Default.withDoubleLock(androidSdkPath / "$packagePath.lock") {
            findInstalledPackage(packagePath) ?: installPackageFromRemote(packagePath)
        }

    private fun findInstalledPackage(packagePath: String): RepoPackage? {
        val installedPackagePath = resolveInstalledPackagePath(packagePath) ?: return null
        val packageManifest = installedPackagePath.toLocalPath() / "package.xml"
        return if (packageManifest.exists()) {
            packageManifest.readRepository().localPackage
        } else {
            installedPackagePath.emptyLocalPackage()
        }
    }

    /**
     * Returns the package path of an already-installed package matching [packagePath] (exactly or as
     * a minor-API-level variant), or null if no matching package is installed.
     */
    private fun resolveInstalledPackagePath(packagePath: String): String? {
        val parentPrefix = packagePath.split(";").dropLast(1)
        val parentDir = parentPrefix.fold(androidSdkPath) { dir, component -> dir.resolve(component) }
        if (!parentDir.exists()) return null
        val installedPackagePaths = parentDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { (parentPrefix + it.name).joinToString(";") }
        return selectBestMatchingPackagePath(packagePath, installedPackagePaths)
    }

    private suspend fun installPackageFromRemote(
        packagePath: String,
        remotePackages: List<RemotePackage>? = null,
    ): RepoPackage {
        val availableRemotePackages = remotePackages ?: packages().remotePackage
        val resolvedPackagePath = selectBestMatchingPackagePath(packagePath, availableRemotePackages.map { it.path })
            ?: error("Package $packagePath not found")
        val pkg = availableRemotePackages.first { it.path == resolvedPackagePath }
        val localPackagePath = resolvedPackagePath.toLocalPath()
        val url = androidRepositoryUrlBuilder.appendPathSegments(pkg.archive.complete.url).build()
        val path = Downloader.downloadFileToCacheLocation(url.toString(), userCacheRoot)
        val cachePath = extractFileToCacheLocation(path, userCacheRoot, ExtractOptions.STRIP_ROOT)
        localPackagePath.createParentDirectories()
        cachePath.copyToRecursively(localPackagePath, followLinks = true)
        return writePackageXml(pkg, localPackagePath)
    }

    private fun String.toLocalPath(): Path =
        split(";").fold(androidSdkPath) { dir, component -> dir.resolve(component) }

    suspend fun installSystemImage(packagePath: String): RepoPackage =
        FileMutexGroup.Default.withDoubleLock(androidSdkPath / "$packagePath.lock") {
            val localFileSystemPackagePath = packagePath
                .split(";")
                .fold(androidSdkPath) { path, component -> path.resolve(component) }

            if (localFileSystemPackagePath.exists()) {
                val packageManifest = localFileSystemPackagePath / "package.xml"
                if (packageManifest.exists()) {
                    packageManifest.readRepository().localPackage
                } else {
                    packagePath.emptyLocalPackage()
                }
            } else {
                installSystemImage(packagePath, localFileSystemPackagePath)
            }
        }

    private fun String.emptyLocalPackage(): com.android.repository.impl.generated.v2.LocalPackage {
        logger.warn("Package is corrupted: $this")
        val pkg = com.android.repository.impl.generated.v2.LocalPackage()
        pkg.path = this
        return pkg
    }

    private suspend fun installSystemImage(packagePath: String, localPackagePath: Path): RepoPackage {
        val pkg = systemImages().remotePackage.firstOrNull { it.path == packagePath }
            ?: error("Package $packagePath not found")
        val url = androidSystemImagesRepositoryUrlBuilder.appendPathSegments(pkg.archive.complete.url).build()
        val path = Downloader.downloadFileToCacheLocation(url.toString(), userCacheRoot)
        val cachePath = extractFileToCacheLocation(path, userCacheRoot, ExtractOptions.STRIP_ROOT)
        localPackagePath.createParentDirectories()
        cachePath.copyToRecursively(localPackagePath, followLinks = true)
        return writePackageXml(pkg, localPackagePath)
    }

    suspend fun packages(): Repository {
        val url = androidRepositoryUrlBuilder.appendPathSegments("/repository2-3.xml").build()
        return amperHttpClient.getRepository(url)
    }

    suspend fun systemImages(): Repository {
        val url = androidSystemImagesRepositoryUrlBuilder.appendPathSegments("/sys-img2-3.xml").build()
        return amperHttpClient.getRepository(url)
    }

    private suspend fun HttpClient.getRepository(url: Url): Repository =
        get(url).bodyAsChannel().toInputStream().use { it.unmarshal<Repository>() }

    suspend fun findUnacceptedSdkLicenseIds(incrementalCache: IncrementalCache): List<String> =
        AndroidSdkLicenseChecker(androidSdkPath, incrementalCache) { packageManifest ->
            packageManifest.readRepository().localPackage.license
        }.findUnacceptedLicenseIds()

    private fun writePackageXml(pkg: RemotePackage, localPackagePath: Path): LocalPackage {
        val localPackage = LocalPackageImpl.create(pkg)
        val factory = pkg.createFactory()
        val repo = factory.createRepositoryType()
        repo.setLocalPackage(localPackage)
        repo.addLicense(pkg.license)
        (localPackagePath / "package.xml").outputStream().use { it.marshal(factory.generateRepository(repo)) }
        return localPackage
    }

    private fun Path.readRepository(): Repository = inputStream().use { it.unmarshal<Repository>() }

    private inline fun <reified T> InputStream.unmarshal(): T = SchemaModuleUtil.unmarshal(
        this,
        listOf(
            AndroidSdkHandler.repositoryModule,
            AndroidSdkHandler.addonModule,
            AndroidSdkHandler.sysImgModule,
            AndroidSdkHandler.commonModule,
            RepoManager.genericModule,
            RepoManager.commonModule,
        ),
        true,
        ConsoleProgressIndicator(),
        ""
    ) as T

    private fun <T> OutputStream.marshal(obj: T) {
        val allModules = setOf(
            AndroidSdkHandler.repositoryModule,
            AndroidSdkHandler.addonModule,
            AndroidSdkHandler.sysImgModule,
            AndroidSdkHandler.commonModule,
            RepoManager.genericModule,
            RepoManager.commonModule,
        )
        val resourceResolver = SchemaModuleUtil.createResourceResolver(allModules, ConsoleProgressIndicator())
        SchemaModuleUtil.marshal(
            obj as JAXBElement<*>,
            allModules,
            this,
            resourceResolver,
            ConsoleProgressIndicator(),
            true,
        )
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}

/**
 * Selects from [available] the package path that best matches [requested].
 *
 * An exact match is preferred. Otherwise, a minor-API-level variant of [requested] is accepted
 * (e.g. `platforms;android-37.0` for the requested `platforms;android-37`), picking the highest
 * available minor API level. This is required because recent Android platforms are published only
 * under a minor-API-level name - there is no plain `platforms;android-37` in the SDK repository.
 *
 * Unrelated variants such as extension levels (e.g. `...-ext19`) or codenames are never matched.
 */
internal fun selectBestMatchingPackagePath(requested: String, available: Collection<String>): String? {
    if (requested in available) return requested
    val minorApiLevelRegex = Regex("${Regex.escape(requested)}\\.(\\d+)")
    return available
        .mapNotNull { candidate ->
            minorApiLevelRegex.matchEntire(candidate)?.let { match -> candidate to match.groupValues[1].toInt() }
        }
        .maxByOrNull { it.second }
        ?.first
}

/**
 * Selects the newest minor-API-level variant of [requested], falling back to an exact match when
 * the repository has no minor-API-level variants.
 */
internal fun selectLatestMinorApiLevelPackagePath(requested: String, available: Collection<String>): String? {
    val extensionIndex = requested.lastIndexOf("-ext")
    val packageName = requested.substring(0, extensionIndex.takeIf { it >= 0 } ?: requested.length)
    val extensionSuffix = requested.substring(extensionIndex.takeIf { it >= 0 } ?: requested.length)
    val minorApiLevelRegex = Regex("${Regex.escape(packageName)}\\.(\\d+)${Regex.escape(extensionSuffix)}")
    return available
        .mapNotNull { candidate ->
            minorApiLevelRegex.matchEntire(candidate)?.let { match -> candidate to match.groupValues[1].toInt() }
        }
        .maxByOrNull { it.second }
        ?.first
        ?: requested.takeIf { it in available }
}
