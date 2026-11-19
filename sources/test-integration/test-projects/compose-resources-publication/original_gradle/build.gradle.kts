/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
}

// The '*.module.original_gradle.json' reference files (see test-integration/amper-cli-test/testResources) keep the
// module metadata that this Gradle project generates. They are not asserted, they are only the reference of what KGP
// produces for the same module as the Kotlin Toolchain module one directory up, so they have to be regenerated
// whenever this project (or its Gradle/Kotlin versions) changes. This task automates that: it copies the
// metadata files verbatim from the build outputs of this project, leaving the review and the commit to the user.
val kotlinToolchainTestResourcesDir = rootDir.parentFile.parentFile.parentFile.resolve("amper-cli-test/testResources")

tasks.register<Copy>("generateOriginalGradleGoldFiles") {
    group = "gold file generation"
    description = "Copies the Gradle module metadata of the 'library' module to its " +
            "'*.module.original_gradle.json' reference files."

    // Copies the module.json of a publication of a module to [goldFileName]. The publication names are the ones KGP
    // generates: 'kotlinMultiplatform' for the root publication of a KMP library, the target name for its platform
    // publications.
    fun gold(modulePath: String, publicationName: String, goldFileName: String) {
        val module = project(modulePath)
        dependsOn(module.tasks.named("generateMetadataFileFor${publicationName.replaceFirstChar { it.uppercase() }}Publication"))
        from(module.layout.buildDirectory.file("publications/$publicationName/module.json")) {
            rename { goldFileName }
        }
    }

    gold(":library", "kotlinMultiplatform", "compose_resources_publication_(kmp_library).module.original_gradle.json")
    gold(":library", "jvm", "compose_resources_publication_(kmp_library).jvm.module.original_gradle.json")
    gold(":library", "wasmJs", "compose_resources_publication_(kmp_library).wasmJs.module.original_gradle.json")

    into(kotlinToolchainTestResourcesDir.resolve("gradleMetadata"))
}
