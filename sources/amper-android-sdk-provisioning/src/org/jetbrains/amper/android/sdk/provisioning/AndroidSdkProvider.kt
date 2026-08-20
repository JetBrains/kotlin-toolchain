/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import com.android.repository.api.ConsoleProgressIndicator
import com.android.repository.api.LocalPackage
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.Repository
import com.android.repository.impl.meta.LocalPackageImpl
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.meta.DetailsTypes
import io.ktor.http.*
import io.opentelemetry.api.OpenTelemetry
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.concurrency.AsyncConcurrentMap
import org.jetbrains.amper.concurrency.StripedFileMutexGroup
import org.jetbrains.amper.concurrency.withDoubleLock
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToLocation
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import javax.xml.bind.JAXBElement
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo

/**
 * Provisions Android SDK packages into [sdkRoot].
 *
 * Archive downloads are shared through [AmperUserCacheRoot], while package installation is synchronized per
 * SDK root and package path. [IncrementalCache] caches both repository XML lists and license validation results.
 */
class AndroidSdkProvider(
    private val userCacheRoot: AmperUserCacheRoot,
    val sdkRoot: Path,
    private val incrementalCache: IncrementalCache,
    openTelemetry: OpenTelemetry,
) {
    private val tracer = openTelemetry.getTracer("org.jetbrains.amper.android.sdk.provisioning")
    private val repositoryXmlListsProvider = AndroidSdkRepositoryXmlListsProvider(
        openTelemetry = openTelemetry,
        userCacheRoot = userCacheRoot,
        incrementalCache = incrementalCache,
    )
    private val repositories = AsyncConcurrentMap<AndroidSdkRepository, Repository>()
    private val packages = AsyncConcurrentMap<AndroidSdkPackageRequest, AndroidSdkResult>()
    private val packagesMutexGroup = StripedFileMutexGroup(256)

    init {
        sdkRoot.createDirectories()
    }

    /**
     * Finds or provisions the SDK package based on the [request].
     *
     * Potential errors in the local SDK storage are reported via [localPackageProblemReporter]
     * (e.g., if the local package is corrupted).
     * The provisioning failures itself are reported as [AndroidSdkResult.Error]s.
     */
    @UsedInIdePlugin
    context(localPackageProblemReporter: ProblemReporter)
    suspend fun provision(request: AndroidSdkPackageRequest): AndroidSdkResult =
        tracer.spanBuilder("Provision Android SDK package $request")
            .use { span ->
                span.setAttribute("from-memory-cache", true)
                packages.computeIfAbsent(request) {
                    span.setAttribute("from-memory-cache", false)
                    val installedPackage = install(request)
                        ?: return@computeIfAbsent AndroidSdkResult.Error("Failed to provision ${request.displayName}")

                    AndroidSdkResult.Success(
                        AndroidSdkPackage(
                            packagePath = installedPackage.packagePath,
                            location = installedPackage.packagePath.toLocalPath(),
                            license = installedPackage.license,
                        )
                    )
                }
            }

    context(_: ProblemReporter)
    private suspend fun install(request: AndroidSdkPackageRequest): RepoPackage? =
        when (request) {
            // TODO: Some packages have no version qualifiers or use "latest".
            //  In that case it might make sense to compare it somehow with the remote version (checksum?).
            AndroidSdkPackageRequest.Emulator -> findAndInstallExactPackage(
                PackagePath("emulator"),
                AndroidSdkRepository.Main,
            )
            is AndroidSdkPackageRequest.CommandLineTools -> findAndInstallExactPackage(
                PackagePath("cmdline-tools;${request.version}"),
                AndroidSdkRepository.Main,
            )
            AndroidSdkPackageRequest.PlatformTools -> findAndInstallExactPackage(
                PackagePath("platform-tools"),
                AndroidSdkRepository.Main,
            )
            is AndroidSdkPackageRequest.BuildTools -> findAndInstallExactPackage(
                PackagePath("build-tools;${request.version}"),
                AndroidSdkRepository.Main,
            )
            is AndroidSdkPackageRequest.Platform -> findAndInstallExactPackage(
                request.packagePath,
                AndroidSdkRepository.Main
            )
            is AndroidSdkPackageRequest.PlatformSources -> findAndInstallExactPackage(
                request.packagePath,
                AndroidSdkRepository.Main,
            )
            is AndroidSdkPackageRequest.SystemImage -> installSystemImage(request)
        }

    private val AndroidSdkPackageRequest.Platform.packagePath: PackagePath
        get() = PackagePath(buildString {
            append("platforms;android-")
            append(platformVersionString(apiLevel, minorApiLevel, sdkExtension))
        })

    private val AndroidSdkPackageRequest.PlatformSources.packagePath: PackagePath
        get() = PackagePath(buildString {
            append("sources;android-")
            append(platformVersionString(apiLevel, minorApiLevel))
        })

    private fun platformVersionString(
        apiLevel: Int,
        minorApiLevel: Int,
        sdkExtension: Int? = null,
    ): String = buildString {
        append(apiLevel)
        if (apiLevel >= 37 || minorApiLevel != 0) {
            // Minor API level 0 started being published at API 37. API 36 has only 36 and 36.1.
            append(".$minorApiLevel")
        }
        sdkExtension?.let { append("-ext$it") }
    }

    context(_: ProblemReporter)
    private suspend fun installSystemImage(request: AndroidSdkPackageRequest.SystemImage): RepoPackage? {
        val lockName = "system-images;android;${request.tag.value};${request.abi.repositoryValue}.lock"
        return packagesMutexGroup.withDoubleLock(sdkRoot / lockName) {
            val localPackage = request.findBestPackageLocally(sdkRoot)
            if (localPackage != null) {
                val deducedPath = PackagePath(localPackage.relativeTo(sdkRoot).joinToString(";"))
                val localPackage = readLocalPackage(deducedPath, localPackage)
                if (localPackage != null) return@withDoubleLock localPackage
                // Fallback to downloading remote package if failed to read it locally
            }

            val remotePackages = getRepository(AndroidSdkRepository.SystemImages).remotePackage
            val alignedName = request.findBestPackageRemotely(remotePackages) ?: return@withDoubleLock null
            installPackageFromRemote(alignedName, remotePackages, AndroidSdkRepository.SystemImages)
        }
    }

    context(_: ProblemReporter)
    private suspend fun findAndInstallExactPackage(
        packagePath: PackagePath,
        repository: AndroidSdkRepository,
    ): RepoPackage? =
        packagesMutexGroup.withDoubleLock(sdkRoot / "$packagePath.lock") {
            val installedPackagePath = packagePath.toLocalPath()
            if (installedPackagePath.isDirectory()) {
                val localPackage = readLocalPackage(packagePath, installedPackagePath)
                if (localPackage != null) return@withDoubleLock localPackage
                // Fallback to downloading remote package if failed to read it locally
            }

            val remotePackages = getRepository(repository).remotePackage
            installPackageFromRemote(packagePath, remotePackages, repository)
        }

    context(problemReporter: ProblemReporter)
    private fun readLocalPackage(packagePath: PackagePath, path: Path): RepoPackage? =
        tracer.spanBuilder("Read local Android SDK package").useWithoutCoroutines {
            val packageManifest = path / "package.xml"
            if (!packageManifest.exists()) {
                problemReporter.reportMessage(LocalPackageWithoutManifest(packagePath, path))
                return@useWithoutCoroutines null
            }
            packageManifest.readRepository().localPackage
        }

    private suspend fun installPackageFromRemote(
        packagePath: PackagePath,
        remotePackages: List<RemotePackage>,
        repository: AndroidSdkRepository,
    ): RepoPackage? {
        val pkg = remotePackages.firstOrNull { it.path == packagePath.path } ?: return null
        return installRemotePackage(pkg, URLBuilder(repository.baseUrl))
    }

    private suspend fun installRemotePackage(pkg: RemotePackage, repositoryBaseUrlBuilder: URLBuilder): RepoPackage {
        val localPackagePath = pkg.packagePath.toLocalPath()
        val url = repositoryBaseUrlBuilder.appendPathSegments(pkg.archive.complete.url).build()
        val path = tracer.spanBuilder("Download Android SDK package ${pkg.path}").use {
            Downloader.downloadFileToCacheLocation(url.toString(), userCacheRoot)
        }
        return tracer.spanBuilder("Install Android SDK package files ${pkg.path}").use {
            // Clean up possible leftovers of the old package
            if (localPackagePath.exists()) localPackagePath.deleteRecursively()
            extractFileToLocation(path, localPackagePath, ExtractOptions.STRIP_ROOT)
            writePackageXml(pkg, localPackagePath)
        }
    }

    private fun PackagePath.toLocalPath(): Path =
        path.split(";").fold(sdkRoot) { dir, component -> dir.resolve(component) }

    private suspend fun getRepository(repository: AndroidSdkRepository): Repository =
        tracer.spanBuilder("Read Android repository ${repository.name}")
            .setAttribute("repository-name", repository.name)
            .setAttribute("repository-url", repository.packageUrl.toString())
            .use { span ->
                span.setAttribute("from-memory-cache", true)
                repositories.computeIfAbsent(repository) {
                    span.setAttribute("from-memory-cache", false)
                    repositoryXmlListsProvider.getRepositoryXml(repository).readRepository()
                }
            }

    private fun writePackageXml(pkg: RemotePackage, localPackagePath: Path): LocalPackage {
        val localPackage = LocalPackageImpl.create(pkg)
        val factory = pkg.createFactory()
        val repo = factory.createRepositoryType()
        repo.setLocalPackage(localPackage)
        repo.addLicense(pkg.license)
        (localPackagePath / "package.xml").outputStream().use { outputStream ->
            tracer.spanBuilder("Write package xml").useWithoutCoroutines {
                outputStream.marshal(factory.generateRepository(repo))
            }
        }
        return localPackage
    }

    private fun Path.readRepository(): Repository = inputStream().use { inputStream ->
        tracer.spanBuilder("Parsing repository from $this").useWithoutCoroutines {
            inputStream.unmarshal<Repository>()
        }
    }

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
}

private val RepoPackage.packagePath: PackagePath get() = PackagePath(path)

private data class LocalSystemImage(val version: ComparableVersion, val servicesRoot: Path)

private fun AndroidSdkPackageRequest.SystemImage.findBestPackageLocally(sdkHome: Path): Path? {
    val systemImagesHome = sdkHome / "system-images"
    if (!systemImagesHome.exists()) return null

    val acceptableVersion = ComparableVersion("$minimalAcceptableApiLevel")
    val (servicesRoot) = systemImagesHome.listDirectoryEntries(glob = "android-*")
        .mapNotNull { imageRoot ->
            val version = ComparableVersion(imageRoot.name.removePrefix("android-"))
            if (version < acceptableVersion) return@mapNotNull null

            val acceptableTag = imageRoot.listDirectoryEntries()
                .sortedBy { it.name } // Sort for consistent choice
                .filter { child ->
                    when (tag) {
                        AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis -> child.name == "google_apis" || child.name == "google_apis_ps16k"
                    }
                }
                .firstOrNull { child ->
                    (child / abi.repositoryValue).exists()
                }
            if (acceptableTag == null) return@mapNotNull null
            LocalSystemImage(version, acceptableTag)
        }
        .maxByOrNull { it.version } ?: return null
    return servicesRoot / abi.repositoryValue
}

private fun AndroidSdkPackageRequest.SystemImage.findBestPackageRemotely(packages: List<RemotePackage>): PackagePath? {
    return packages.filter { remotePackage ->
        val typeDetails = remotePackage.typeDetails as? DetailsTypes.SysImgDetailsType ?: return@filter false
        // We can't use channel as an indicator of stable package because beta packages are published to the stable channel
        if (remotePackage.path.contains("beta") || remotePackage.path.contains("dev")) return@filter false

        // We want to download the latest available system image so that it's suitable for most of the projects later on
        typeDetails.apiLevel >= minimalAcceptableApiLevel &&
                typeDetails.abis.contains(abi.repositoryValue) &&
                typeDetails.tags.map { it.id }.contains(tag.value)
    }.maxByOrNull { remotePackage ->
        val typeDetails = remotePackage.typeDetails as DetailsTypes.SysImgDetailsType // already filtered above
        ComparableVersion(typeDetails.apiLevelString)
    }?.packagePath
}
