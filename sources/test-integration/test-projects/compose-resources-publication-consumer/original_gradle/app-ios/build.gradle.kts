/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

// The Amper module is an 'ios/app' product: it builds the Kotlin framework AND the Xcode app around it. Gradle only
// covers the framework part; the Xcode project would live outside the build and call 'embedAndSignAppleFrameworkForXcode'
// plus 'syncComposeResourcesForIos'. Since only the resources are of interest here, the Xcode shell is left out.
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "KotlinModules"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Amper adds these two implicitly for any module with 'settings.compose.enabled: true'.
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
            // '$compose.foundation' in the Amper module
            implementation(libs.compose.foundation)
            // The library published by the Kotlin Toolchain (see the 'compose-resources-publication' test project).
            implementation("org.example:library-native:1.0.0")
        }
    }
}

compose.resources {
    // 'settings.compose.resources.packageName' in the Amper module
    packageOfResClass = "com.example.appios.gen"
    // 'settings.compose.resources.exposedAccessors' in the Amper module
    publicResClass = true
    generateResClass = always
}
