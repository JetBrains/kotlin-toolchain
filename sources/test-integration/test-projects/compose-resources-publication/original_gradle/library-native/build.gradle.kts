/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    `maven-publish`
}

// Matches the 'publishing' settings of the Amper module of the same name: the root publication is
// 'org.example:library-native:1.0.0' (the artifact ID comes from the Gradle project name).
group = "org.example"
version = "1.0.0"

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Amper adds these two implicitly for any module with 'settings.compose.enabled: true' and resources.
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
        }
    }
}

compose.resources {
    // 'settings.compose.resources.packageName' in the Amper module
    packageOfResClass = "com.example.libnative.gen"
    // 'settings.compose.resources.exposedAccessors' in the Amper module
    publicResClass = true
    generateResClass = always
}

publishing {
    repositories {
        // The Amper module publishes to 'mavenLocal', which is what 'publishToMavenLocal' does here.
        // Pass -Dmaven.repo.local=<dir> to redirect it to a scratch directory.
        mavenLocal()
    }
}
