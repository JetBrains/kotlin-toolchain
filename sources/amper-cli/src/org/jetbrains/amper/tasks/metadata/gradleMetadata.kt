/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.attributes.ArtifactType
import org.jetbrains.amper.dependency.resolution.attributes.Category
import org.jetbrains.amper.dependency.resolution.attributes.DependencyBundling
import org.jetbrains.amper.dependency.resolution.attributes.JvmEnvironment
import org.jetbrains.amper.dependency.resolution.attributes.KotlinNativeTarget
import org.jetbrains.amper.dependency.resolution.attributes.KotlinPlatformType
import org.jetbrains.amper.dependency.resolution.attributes.KotlinWasmTarget
import org.jetbrains.amper.dependency.resolution.attributes.Usage
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverrides
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.tasks.MavenPublishable
import org.jetbrains.amper.tasks.mavenFileName
import org.jetbrains.amper.tasks.native.cinteropName
import org.jetbrains.amper.tasks.rootFragment
import org.jetbrains.gradle.module.metadata.format.AvailableAt
import org.jetbrains.gradle.module.metadata.format.Component
import org.jetbrains.gradle.module.metadata.format.CreatedBy
import org.jetbrains.gradle.module.metadata.format.File
import org.jetbrains.gradle.module.metadata.format.KotlinToolchain
import org.jetbrains.gradle.module.metadata.format.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.fileSize

/**
 * Suffix is added to the Gradle Metadata variant name.
 * It is an implementation detail and is a subject to change,
 * see https://docs.google.com/document/d/18MsmrX2iYuoKS3HvY-_xnk_SMenLQzSPArj4yuy1Hfw/edit?tab=t.0
 */
private const val PUBLISHED_SUFFIX = "-published"

internal suspend fun generateCommonGradleModuleMetadata(
    module: AmperModule,
    outputDir: Path,
    allMetadataJarPath: Path?,
    allMetadataSourcesJarPath: Path?,
    checksums: Map<String, List<MavenPublishable>>,
): Path {
    val leafPlatformVariants: List<GradleVariant> = module.leafFragments
        .filterNot { it.isTest }
        .sortedBy { it.name }
        .flatMap { leafFragment ->
            buildList {
                val scopes = getApplicableVariantScopes(leafFragment)
                addAll(scopes.map { leafFragment.toGradleMetadataAvailableAtVariant(it) })

                if (module.publishingSettings.publishSources) {
                    add(leafFragment.toGradleMetadataAvailableAtVariant(ResolutionScope.RUNTIME, isSources = true))
                }
            }
        }

    val allMetadataVariants = buildList {
        if (allMetadataJarPath != null) {
            add(allMetadataVariant(module, allMetadataJarPath, checksums))
        }
        if (module.publishingSettings.publishSources && allMetadataSourcesJarPath != null) {
            add(allMetadataSourcesVariant(module, allMetadataSourcesJarPath, checksums))
        }
    }

    val variants = allMetadataVariants + leafPlatformVariants

    return generateGradleModuleFile(variants, Platform.COMMON, module, outputDir)
}

private fun allMetadataVariant(
    module: AmperModule,
    allMetadataJarPath: Path,
    checksums: Map<String, List<MavenPublishable>>,
): GradleVariant {
    val fragments = module.allMetadataFragments().ifEmpty { [module.rootFragment] }
    val dependencies = fragments
        .flatMap { it.classPathForApiMetadata() }
        // Dependencies in Gradle module metadata of KMP project are declared in terms of common libraries root coordinates.
        // Deduplicate after this conversion because notation equality includes source traces.
        .mapNotNull { it.toVariantDependency(Platform.COMMON) }
        .distinct()
        .toList()

    // Passing Platform.COMMON because
    // Gradle metadata descriptor is aware about KMP, there is no need to adjust coordinates of dependencies
    val platformSpecificCoordinates = module.publicationCoordinates(Platform.COMMON)

    val artifactId = platformSpecificCoordinates.artifactId
    val version = platformSpecificCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

    return GradleVariant(
        name = "metadataApiElements",
        attributes = buildMap {
            this[Category.name] = Category.Library.value
            this[JvmEnvironment.name] = JvmEnvironment.NonJvm.value
            this[Usage.name] = Usage.KotlinMetadata.value
            this[KotlinPlatformType.name] = KotlinPlatformType.fromPlatform(ResolutionPlatform.COMMON).value
        },
        dependencies = dependencies,
        dependencyConstraints = [],
        files = [
            File(
                name = "${module.userReadableName}-metadata-$version.jar",
                url = "$artifactId-$version.jar",
                size = allMetadataJarPath.fileSize(),
                sha512 = getCheckSumFor(allMetadataJarPath, "sha512", checksums),
                sha256 = getCheckSumFor(allMetadataJarPath, "sha256", checksums),
                sha1 = getCheckSumFor(allMetadataJarPath, "sha1", checksums),
                md5 = getCheckSumFor(allMetadataJarPath, "md5", checksums),
            )
        ],
        `available-at` = null,
        capabilities = [],
    )
}

private fun getCheckSumFor(
    allMetadataJarPath: Path,
    algorithm: String,
    checksums: Map<String, List<MavenPublishable>>,
): String? {
    val checksums = (checksums[allMetadataJarPath.toAbsolutePath().toString()]
        ?: error("Checksums for the all metadata jar $allMetadataJarPath were not calculated"))
    val checkSum = checksums
        .singleOrNull { it.path.fileName.toString().endsWith(".$algorithm") }
        ?.let { Files.readString(it.path) }
    return checkSum
}

private fun allMetadataSourcesVariant(
    module: AmperModule,
    allMetadataSourcesJarPath: Path,
    checksums: Map<String, List<MavenPublishable>>,
): GradleVariant {
    // Passing Platform.COMMON because
    // Gradle metadata descriptor is aware about KMP, there is no need to adjust coordinates of dependencies
    val platformSpecificCoordinates = module.publicationCoordinates(Platform.COMMON)

    val artifactId = platformSpecificCoordinates.artifactId
    val version = platformSpecificCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

    return GradleVariant(
        name = "metadataSourcesElements",
        attributes = buildMap {
            this[Category.name] = Category.Documentation.value
            this[DependencyBundling.name] = DependencyBundling.External.value
            this["org.gradle.docstype"] = "sources"
            this[JvmEnvironment.name] = JvmEnvironment.NonJvm.value
            this[Usage.name] = Usage.KotlinRuntime.value
            this[KotlinPlatformType.name] = KotlinPlatformType.fromPlatform(ResolutionPlatform.COMMON).value
        },
        files = [
            File(
                name = "${module.userReadableName}-kotlin-$version-sources.jar",
                url = "$artifactId-$version-sources.jar",
                size = allMetadataSourcesJarPath.fileSize(),
                sha512 = getCheckSumFor(allMetadataSourcesJarPath, "sha512", checksums),
                sha256 = getCheckSumFor(allMetadataSourcesJarPath, "sha256", checksums),
                sha1 = getCheckSumFor(allMetadataSourcesJarPath, "sha1", checksums),
                md5 = getCheckSumFor(allMetadataSourcesJarPath, "md5", checksums),
            )
        ],
    )
}

private fun LeafFragment.toGradleMetadataAvailableAtVariant(
    scope: ResolutionScope,
    isSources: Boolean = false,
): GradleVariant {
    val platformSpecificCoordinates = module.publicationCoordinates(platform)

    val groupId = platformSpecificCoordinates.groupId
    val artifactId = platformSpecificCoordinates.artifactId
    val version = platformSpecificCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

    val baseVariant = toBaseGradleVariant(scope, isSources)

    return baseVariant.copy(

        // no dependencies or constraints are declared for the leaf target fragment,
        // those are declared for the corresponding 'available-at' .module
        dependencies = [],
        dependencyConstraints = [],
        files = [],
        `available-at` = AvailableAt(
            url = "../../$artifactId/$version/$artifactId-$version.module",
            group = groupId,
            module = artifactId,
            version = version
        ),
        capabilities = [],
    )
}

/**
 * Generates the Gradle module metadata file for the given leaf [platform] of the given [module].
 *
 * [platformSpecificArtifact] is the main artifact published for this platform (a jar, a klib, etc.), or null if the
 * module doesn't produce any (which is the case for modules without sources). In the latter case, the variants of
 * this platform simply have no main file.
 */
suspend fun generateGradleMetadataForLeafPlatform(
    module: AmperModule,
    platform: Platform,
    outputDir: Path,
    checksums: Map<String, List<MavenPublishable>>,
    platformSpecificArtifact: MavenPublishable?,
    platformSpecificCinteropArtifacts: List<MavenPublishable>,
    platformSpecificSourcesJar: MavenPublishable? = null,
    overrides: PublicationCoordinatesOverrides,
): Path {
    val leafFragment: LeafFragment = module.leafFragments
        .filterNot { it.isTest }
        .single { it.platform == platform }

    val variants: List<GradleVariant> = buildList {
        val scopes = getApplicableVariantScopes(leafFragment)
        scopes.forEach { scope ->
            val mainArtifact = platformSpecificArtifact?.toGradleMetadataFile(leafFragment, checksums = checksums)
            val cinteropArtifacts = platformSpecificCinteropArtifacts.map { it.toGradleMetadataFile(leafFragment, checksums = checksums) }
            add(leafFragment.toGradleVariant(scope, isSources = false, listOfNotNull(mainArtifact) + cinteropArtifacts, overrides))
        }

        if (module.publishingSettings.publishSources && platformSpecificSourcesJar != null) {
            // The sources-JAR itself must be described here, not the main artifact:
            // both the file extension and the checksums/size are taken from the described file.
            val sourcesFile = platformSpecificSourcesJar.toGradleMetadataFile(leafFragment, checksums = checksums)
            add(leafFragment.toGradleVariant(ResolutionScope.RUNTIME, isSources = true, [ sourcesFile ], overrides))
        }
    }

    return generateGradleModuleFile(variants, platform, module, outputDir)
}

private fun MavenPublishable.toGradleMetadataFile(
    fragment: LeafFragment,
    checksums: Map<String, List<MavenPublishable>>,
): File {
    val platformSpecificArtifact = this.path
    val platformSpecificCoordinates = this.coordinates
    val isCinterop = platformSpecificCoordinates.isCinteropClassifier
    val module = fragment.module

    val artifactId = platformSpecificCoordinates.artifactId
    val version = platformSpecificCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")
    val classifierSuffix = platformSpecificCoordinates.classifier?.let { "-$it" } ?: ""
    val extension = platformSpecificArtifact.extension

    val name = if (fragment.platform == Platform.JVM && !module.isMultiplatformPublication()) {
        // JVM-only publication
        "$artifactId-$version$classifierSuffix.$extension"
    } else {
        if (isCinterop) {
            // Regular KMP publication of a cinterop klib, follows the KGP naming convention:
            // name 'atomicfu-linuxX64Cinterop-interopMain-0.32.1.klib',
            // url 'atomicfu-linuxx64-0.32.1-cinterop-interop.klib'.
            val interopName = platformSpecificArtifact.cinteropName()
            "${module.userReadableName}-${fragment.name}Cinterop-${interopName}Main-$version.$extension"
        } else {
            // Regular KMP publication
            "${module.userReadableName}-${fragment.sourceSetName()}-$version$classifierSuffix.$extension"
        }
    }

    // The URL is the artifact's file name in the Maven repository layout, so it must match the name used for the
    // published file itself, as well as for its checksums and signature.
    val url = platformSpecificCoordinates.mavenFileName(extension)

    return File(
        name = name,
        url = url,
        size = platformSpecificArtifact.fileSize(),
        sha512 = getCheckSumFor(platformSpecificArtifact, "sha512", checksums),
        sha256 = getCheckSumFor(platformSpecificArtifact, "sha256", checksums),
        sha1 = getCheckSumFor(platformSpecificArtifact, "sha1", checksums),
        md5 = getCheckSumFor(platformSpecificArtifact, "md5", checksums),
    )
}

private const val CINTEROP_CLASSIFIER_PREFIX = "cinterop-"

/**
 * The Maven classifier used to publish the cinterop klib at this path.
 *
 * Follows the KGP convention, e.g. `atomicfu-linuxx64-0.32.1-cinterop-interop.klib`.
 */
internal fun Path.cinteropClassifier() = "$CINTEROP_CLASSIFIER_PREFIX${cinteropName()}"

/**
 * Whether these coordinates point at a cinterop klib published by [cinteropClassifier].
 */
internal val MavenCoordinates.isCinteropClassifier: Boolean
    get() = classifier?.startsWith(CINTEROP_CLASSIFIER_PREFIX) == true

private fun LeafFragment.toBaseGradleVariant(
    scope: ResolutionScope,
    isSources: Boolean = false,
): GradleVariant {
    val nameSuffix = when {
        isSources -> "Sources"
        else -> when (scope) {
            ResolutionScope.COMPILE -> "Api"
            ResolutionScope.RUNTIME -> "Runtime"
        }
    }
    val name =
        if (platform == Platform.JVM && !module.isMultiplatformPublication()) {
            "${nameSuffix.lowercase()}Elements"
        } else {
            "${name}${nameSuffix}Elements$PUBLISHED_SUFFIX"
        }

    return GradleVariant(
        name = name,
        attributes = buildMap {
            val resolutionPlatform = platform.toResolutionPlatform()!!

            if (resolutionPlatform.type == PlatformType.NATIVE && !isSources) {
                this[ArtifactType.name] = ArtifactType.KLib.value
            }
            this[Category.name] = if (!isSources) Category.Library.value else Category.Documentation.value
            if (isSources) {
                this[DependencyBundling.name] = DependencyBundling.External.value
                this["org.gradle.docstype"] = "sources"
            } else {
                if (platform == Platform.JVM && !module.isMultiplatformPublication()) {
                    this[DependencyBundling.name] = DependencyBundling.External.value
                    // todo (AB): Value of attribute 'org.gradle.jvm.version' is Int (but Map<String, String> is supported only)
//                    settings.jvm.release?.let { this["org.gradle.jvm.version"] = it }
                }
            }
            if (module.isMultiplatformPublication()) {
                this[JvmEnvironment.name] = JvmEnvironment.fromPlatform(resolutionPlatform).value
            }
            if (resolutionPlatform.type != PlatformType.NATIVE
                && resolutionPlatform.type != PlatformType.WASM
            ) {
                this["org.gradle.libraryelements"] =
                    if (resolutionPlatform.type == PlatformType.ANDROID_JVM && !isSources) "aar" else "jar"
            }
            this[Usage.name] = Usage.fromPlatformAndScope(
                resolutionPlatform,
                if (isSources) ResolutionScope.RUNTIME else scope
            ).value
            KotlinNativeTarget.fromPlatform(resolutionPlatform)?.also {
                this[KotlinNativeTarget.name] = it.value
            }
            if (module.isMultiplatformPublication()) {
                this[KotlinPlatformType.name] = KotlinPlatformType.fromPlatform(resolutionPlatform).value
            }

            KotlinWasmTarget.fromPlatform(resolutionPlatform)?.also {
                this[KotlinWasmTarget.name] = it.value
            }
        },
        dependencies = [],
        dependencyConstraints = [],
        files = [],
        `available-at` = null,
        capabilities = [],
    )
}

private fun LeafFragment.toGradleVariant(
    scope: ResolutionScope,
    isSources: Boolean = false,
    files: List<File>,
    overrides: PublicationCoordinatesOverrides,
): GradleVariant {

    val baseVariant = toBaseGradleVariant(scope, isSources)

    val dependencies = if (isSources) {
        []
    } else {
        dependenciesAvailableForConsumer(scope = scope)
            .distinct()
            .mapNotNull {
                val [platform, overrides] = if (module.isMultiplatformPublication())
                // KMP project declares dependencies in terms of common libraries root coordinates.
                    Platform.COMMON to null
                else
                // non-KMP project declares dependencies in terms of platform-specific coordinates.
                    platform to overrides
                it.toVariantDependency(platform, overrides)
            }
            .toList()
    }

    return baseVariant.copy(
        dependencies = dependencies,
        dependencyConstraints = [],
        files = files,
        `available-at` = null,
        capabilities = [],
    )
}

suspend fun generateGradleModuleFile(variants: List<GradleVariant>, platform: Platform, module: AmperModule, outputDir: Path): Path {
    val commonCoordinates = module.publicationCoordinates(Platform.COMMON)
    val commonArtifactId = commonCoordinates.artifactId
    val commonGroupId = commonCoordinates.groupId
    val version = commonCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

    val gradleMetadata = Module(
        formatVersion = "1.1",
        component = Component(
            url =
                if (platform != Platform.COMMON && module.isMultiplatformPublication())
                    "../../$commonArtifactId/$version/$commonArtifactId-$version.module"
                else
                    null,
            group = commonGroupId,
            module = commonArtifactId,
            version = version,
            attributes = mapOf("org.gradle.status" to "release")
        ),
        createdBy = CreatedBy(
            // "Module.createdBy" accepts anything, not Gradle/Maven only.
            // Gradle will not attempt to resolve or validate it — it skips the whole createdBy node during parsing
            // since it is purely informational/diagnostic metadata.
            kotlinToolchain = KotlinToolchain(
                version = AmperBuild.mavenVersion,
            )
        ),
        variants = variants
    )

    outputDir.createDirectories()

    val platformSpecificCoordinates = module.publicationCoordinates(platform)
    val platformSpecificArtifactId = platformSpecificCoordinates.artifactId

    val gradleMetadataPath = outputDir / "$platformSpecificArtifactId-$version.module"

    withContext(Dispatchers.IO) {
        Files.writeString(
            gradleMetadataPath,
            json.encodeToString(gradleMetadata),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    return gradleMetadataPath
}
