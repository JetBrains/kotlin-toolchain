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
include(":edgeCase_noSources")
include(":edgeCase_wasmJsPlusWasmWasi")