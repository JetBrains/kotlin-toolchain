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
        // Only the locally published sample library comes from ~/.m2. Without this filter, incomplete local copies of
        // common libraries shadow Maven Central and break variant matching.
        mavenLocal {
            content {
                includeGroup("org.jetbrains.kotlintoolchain.swiftpm.sample")
            }
        }
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
}

// Matches the name of the Amper module one directory up.
rootProject.name = "consumer"
