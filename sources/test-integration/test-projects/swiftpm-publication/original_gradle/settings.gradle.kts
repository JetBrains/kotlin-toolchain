/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

pluginManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
}

// The artifact ID of a Gradle publication is the project name, and the Amper module one directory up overrides its
// artifact ID to 'swiftPMPublication', so the root project has to carry that name to publish the same coordinates.
rootProject.name = "swiftPMPublication"
