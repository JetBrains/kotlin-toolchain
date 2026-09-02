/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

// The Amper module is a 'wasm-js/app' product, so it has a single wasmJs target with an executable binary.
kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // Amper adds these two implicitly for any module with 'settings.compose.enabled: true'.
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
            // The library published by the Kotlin Toolchain (see the 'compose-resources-publication' test project).
            implementation("org.example:library:1.0.0")
        }
    }
}

compose.resources {
    // 'settings.compose.resources.packageName' in the Amper module
    packageOfResClass = "com.example.app.gen"
    // 'settings.compose.resources.exposedAccessors' in the Amper module
    publicResClass = true
    generateResClass = always
}
