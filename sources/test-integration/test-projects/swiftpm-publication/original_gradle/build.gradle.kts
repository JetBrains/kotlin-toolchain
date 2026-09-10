/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

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
