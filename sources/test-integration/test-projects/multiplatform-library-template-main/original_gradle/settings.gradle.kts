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
        google()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
}

rootProject.name = "multiplatform-library-template"
include(":library")
include(":libraryCinterop")
include(":libraryNested")
include(":linuxWindowsShared")
include(":nativeShared")
include(":nativePlatform")
include(":edgeCase_jvmLib")
include(":edgeCase_jvmPlusAndroid")
include(":edgeCase_kmpSinglePlatform")
// KGP can't generate the linuxX64 publication of 'edgeCase_noSources' (a module without sources compiles no klib
// for its native target), so 'publishToMavenLocal' fails on it. Exclude it with
// '-x :edgeCase_noSources:publishLinuxX64PublicationToMavenLocal' to publish everything else. The module is still
// included, because the gold reference file generation only requests the publications that KGP can generate.
include(":edgeCase_noSources")
include(":edgeCase_wasmJsPlusWasmWasi")