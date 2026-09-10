/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // A single platform, like the Amper module one directory up.
    iosArm64()

    sourceSets {
        commonMain.dependencies {
            // The library published by the 'swiftpm-publication' project. It declares SwiftPM packages, which this
            // consumer must inherit through the 'swiftPMDependenciesMetadataElements' variant of that publication.
            implementation("org.jetbrains.kotlintoolchain.swiftpm.sample:swiftPMPublication:1.0.0")
        }
    }
}
