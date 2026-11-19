/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import groovy.json.JsonOutput
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

// Matches the 'settings.publishing' of the Amper module one directory up.
group = "org.jetbrains.kotlintoolchain.swiftpm.sample"
version = "1.0.0"

kotlin {
    iosArm64()
    macosArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    swiftPMDependencies {
        // The 'dependencies' of the Amper module: declared in the common fragment, so it applies to every Apple
        // target of the module, which means no platform constraint.
        localSwiftPackage(
            directory = layout.projectDirectory.dir("../commonPackage"),
            products = listOf("CommonProduct"),
        )
        // The 'dependencies@ios' of the Amper module. This DSL has no per-fragment dependencies, so the iOS-only
        // scope is expressed here as an explicit platform constraint on the product instead.
        localSwiftPackage(
            directory = layout.projectDirectory.dir("../iosOnlyPackage"),
            products = listOf(product("IosOnlyProduct", platforms = setOf(iOS()))),
        )
    }
}

publishing {
    repositories {
        // The Amper module publishes to 'mavenLocal', which is what 'publishToMavenLocal' does here.
        // Pass -Dmaven.repo.local=<dir> to redirect it to a scratch directory.
        mavenLocal()
    }
}

// The '*.original_gradle.json' reference files (see test-integration/amper-cli-test/testResources) keep the metadata
// that this Gradle project generates. They are not asserted, they are only the reference of what KGP produces for
// the same module as the Kotlin Toolchain module one directory up, so they have to be regenerated
// whenever this project (or its Gradle/Kotlin versions) changes. The tasks below automate that: they copy the
// metadata files from the build outputs of this project, leaving the review and the commit to the user.
val kotlinToolchainTestResourcesDir = rootDir.parentFile.parentFile.parentFile.resolve("amper-cli-test/testResources")

tasks.register<Copy>("generateModuleMetadataGoldFiles") {
    group = "gold file generation"
    description = "Copies the Gradle module metadata of the root publication to its " +
            "'.module.original_gradle.json' reference file."

    dependsOn(tasks.named("generateMetadataFileForKotlinMultiplatformPublication"))
    from(layout.buildDirectory.file("publications/kotlinMultiplatform/module.json")) {
        rename { "swiftpm_metadata_is_published_in_a_dedicated_variant.module.original_gradle.json" }
    }
    into(kotlinToolchainTestResourcesDir.resolve("gradleMetadata"))
}

// The SwiftPM metadata is only written by the publish task, to the local repository (KGP generates no intermediate
// file for it in the build directory), so this task has to publish to read it. It is pretty-printed to match the
// formatting of the reference files of the other metadata kinds.
val localRepository = publishing.repositories.withType<MavenArtifactRepository>().single { it.url.scheme == "file" }
val publishedSwiftPMMetadata = File(
    localRepository.url
        .resolve("org/jetbrains/kotlintoolchain/swiftpm/sample/swiftPMPublication/1.0.0/swiftPMPublication-1.0.0-swiftpm-metadata.json")
)

tasks.register("generateSwiftPMMetadataGoldFiles") {
    group = "gold file generation"
    description = "Copies the published SwiftPM metadata to its '.swiftpm-metadata.original_gradle.json' reference file."

    dependsOn(tasks.named("publishToMavenLocal"))
    inputs.file(publishedSwiftPMMetadata)
    val goldFile = kotlinToolchainTestResourcesDir.resolve(
        "gradleMetadata/swiftpm_metadata_is_published_in_a_dedicated_variant.swiftpm-metadata.original_gradle.json")
    outputs.file(goldFile)
    doLast {
        val prettyPrinted = JsonOutput.prettyPrint(publishedSwiftPMMetadata.readText())
        // Groovy pretty-prints with 4-space indentation, shifts the whole document by one level and spreads empty
        // arrays over three lines, while the reference files next to the other metadata kinds use 2 spaces without
        // the shift and keep empty arrays inline, so the formatting is adjusted to match them.
        val twoSpaceIndented = prettyPrinted.lineSequence().joinToString("\n") { line ->
            Regex("^((?:    )+)").replace(line) { "  ".repeat(it.value.length / 4) }
        }
        val inlineEmptyArrays = Regex("\\[\\s*\\]").replace(twoSpaceIndented, "[]")
        goldFile.writeText(inlineEmptyArrays + "\n")
    }
}

tasks.register("generateOriginalGradleGoldFiles") {
    group = "gold file generation"
    description = "Regenerates the '*.original_gradle.json' reference files."
    dependsOn(
        tasks.named("generateModuleMetadataGoldFiles"),
        tasks.named("generateSwiftPMMetadataGoldFiles"),
    )
}
