/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.buildinfo.AmperBuild
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
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.tasks.MavenPublishable
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
    val dependencies = module
        .allMetadataFragments()
        .flatMap { it.classPathForApiMetadata() }
        .distinct()
        .map { it.toVariantDependency(Platform.COMMON) }
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
                name = "$artifactId-metadata-$version.jar",
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
                name = "$artifactId-kotlin-$version-sources.jar",
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

suspend fun generateGradleMetadataForLeafPlatform(
    module: AmperModule,
    platform: Platform,
    outputDir: Path,
    checksums: Map<String, List<MavenPublishable>>,
    platformSpecificArtifact: Path,
    platformSpecificSourcesJar: Path? = null,
): Path {
    val leafFragment: LeafFragment = module.leafFragments
        .filterNot { it.isTest }
        .single { it.platform == platform }

    val variants: List<GradleVariant> = buildList {
        val scopes = getApplicableVariantScopes(leafFragment)
        addAll(scopes.map { leafFragment.toGradleVariant(it, isSources = false, checksums = checksums, platformSpecificArtifact) })

        if (module.publishingSettings.publishSources && platformSpecificSourcesJar != null) {
            add(leafFragment.toGradleVariant(ResolutionScope.RUNTIME, isSources = true, checksums, platformSpecificSourcesJar))
        }
    }

    return generateGradleModuleFile(variants, platform, module, outputDir)
}

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

    return GradleVariant(
        name = "${name}${nameSuffix}Elements$PUBLISHED_SUFFIX",
        attributes = buildMap {
            val resolutionPlatform = platform.toResolutionPlatform()!!

            if (resolutionPlatform.type == PlatformType.NATIVE && !isSources) {
                this[ArtifactType.name] = ArtifactType.KLib.value
            }
            this[Category.name] = if (!isSources) Category.Library.value else Category.Documentation.value
            if (isSources) {
                this[DependencyBundling.name] = DependencyBundling.External.value
                this["org.gradle.docstype"] = "sources"
            }
            this[JvmEnvironment.name] = JvmEnvironment.fromPlatform(resolutionPlatform).value
            if (resolutionPlatform.type != PlatformType.NATIVE) {
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
            KotlinWasmTarget.fromPlatform(resolutionPlatform)?.also {
                this[KotlinWasmTarget.name] = it.value
            }
            this[KotlinPlatformType.name] = KotlinPlatformType.fromPlatform(resolutionPlatform).value
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
    checksums: Map<String, List<MavenPublishable>>,
    platformSpecificArtifact: Path,
): GradleVariant {

    val baseVariant = toBaseGradleVariant(scope, isSources)

    val dependencies = if (isSources) {
        []
    } else {
        classPathForApiMetadata()
            .distinct()
            // KMP dependencies are declared in terms of common libraries root coordinates
            .map { it.toVariantDependency(Platform.COMMON) }
            .toList()
    }

    val platformSpecificCoordinates = module.publicationCoordinates(platform)
    val artifactId = platformSpecificCoordinates.artifactId
    val version = platformSpecificCoordinates.version
        ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

    val [name, url] = if (isSources) {
        "$artifactId-$version-sources.jar" to "$artifactId-$version-sources.jar"
    } else {
        val extension = platformSpecificArtifact.extension
        "${module.userReadableName}-${sourceSetName()}-$version.$extension" to "$artifactId-$version.$extension"
    }

    return baseVariant.copy(
        dependencies = dependencies,
        dependencyConstraints = [],
        files = [
            File(
                name = name,
                url = url,
                size = platformSpecificArtifact.fileSize(),
                sha512 = getCheckSumFor(platformSpecificArtifact, "sha512", checksums),
                sha256 = getCheckSumFor(platformSpecificArtifact, "sha256", checksums),
                sha1 = getCheckSumFor(platformSpecificArtifact, "sha1", checksums),
                md5 = getCheckSumFor(platformSpecificArtifact, "md5", checksums),
            )
        ],
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
            url = if (platform != Platform.COMMON) "../../$commonArtifactId/$version/$commonArtifactId-$version.module" else null,
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