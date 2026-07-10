/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.KotlinProjectStructureMetadata
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.ProjectStructure
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.SourceSet
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.tasks.MetadataCompileTask
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div

private const val KOTLIN_PROJECT_STRUCTURE_METADATA_FILE_NAME = "kotlin-project-structure-metadata.json"

// todo (AB) : Wrap into incremental cache (perhaps all together with all-metadata jar ceation)
internal suspend fun generateKotlinProjectDescriptor(
    module: AmperModule,
    outputDir: Path,
    fragmentMetadata: Map<Fragment, MetadataCompileTask.Result>,
): Path {
    // There is an entry for each module LEAF fragment in the project descriptor file.
    // Each entry contains a name of the variant from the Gradle metadata file that corresponds to the fragment
    // and a list of multiplatform fragments (represented with [SourceSet] this fragment depends on).
    val variants = module.leafFragments
        .filterNot { it.isTest }
        .flatMap { leafFragment ->
            val scopes = getApplicableVariantScopes(leafFragment)
            scopes.map { leafFragment.toProjectStructureVariant(it) }
        }
        .sortedBy { it.name }

    // intermediate source sets are declared in 'sourceSets' sections
    val sourceSets = fragmentMetadata
        .map { it.key.toSourceSet() }
        .sortedBy { it.name }

    val projectStructure = KotlinProjectStructureMetadata(
        ProjectStructure(
            formatVersion = "0.3.3",
            isPublishedAsRoot = "true",
            variants = variants,
            sourceSets = sourceSets,
        )
    )

    outputDir.createDirectories()

    val projectStructureFilePath = outputDir / KOTLIN_PROJECT_STRUCTURE_METADATA_FILE_NAME

    withContext(Dispatchers.IO) {
        Files.writeString(
            projectStructureFilePath,
            json.encodeToString(projectStructure),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    return projectStructureFilePath
}

private fun Fragment.toSourceSet(): SourceSet {
    // KGP adds neither compileOnly nor runtimeOnly dependencies to the source set deps (see  OSIP-667),
    // but it adds implementation dependencies for shared fragments
    // (if a project have more than 1 native target platforms).
    // Kotlin Toolchain adds transitive compile dependencies (both api and implementation in terms of Gradle)
    // for pure native shared fragments only.
    // Mixed fragments stays are still provided with exported compile dependencies only (api in terms of Gradle).
    // This differs from KGP, but in fact KGP doesn't use transitive non-exported dependencies of mixed shared fragments.
    // Those got filtered out on a consumer side (by intersection with leaf-platform classpath)
    val dependencies = classPathForApiMetadata()
        .map { it.toVariantDependency(Platform.COMMON) }
        .map { "${it.group}:${it.module}" }
        .distinct()

    return SourceSet(
        name = sourceSetName(),
        dependsOn = fragmentDependencies.filter { it.type == FragmentDependencyType.REFINE }
            .map { it.target.sourceSetName() }
            .toList(),
        moduleDependency = dependencies,
        sourceSetCInteropMetadataDirectory = if (isNative()) "${sourceSetName()}-cinterop" else null,
        binaryLayout = "klib",
        hostSpecific = null,
    )
}

private fun Fragment.toProjectStructureVariant(scope: ResolutionScope): KotlinProjectStructureVariant {
    return KotlinProjectStructureVariant(
        name = "${name}${scope.toVariantSuffix()}Elements",
        sourceSet = allFragmentDependencies(dependencyType = FragmentDependencyType.REFINE)
            .mapNotNull { it.takeIf { it.platforms.size > 1 }?.sourceSetName() }
            .toList()
    )
}

private fun Fragment.isNative() = platforms.all { it.toResolutionPlatform()?.type == PlatformType.NATIVE }