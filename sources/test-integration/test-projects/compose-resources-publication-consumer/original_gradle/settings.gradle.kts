/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

pluginManagement {
    repositories {
        google()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Only the libraries published by the 'compose-resources-publication' project come from ~/.m2. Without this
        // filter, incomplete local copies of common libraries shadow the remote ones and break variant matching.
        mavenLocal {
            content {
                includeGroup("org.example")
            }
        }
        // androidx.* artifacts (pulled in transitively by the Compose runtime) only live here.
        google()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
}

rootProject.name = "compose-resources-publication-consumer"
include(":app")
include(":app-ios")
