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
include(":nonNativeShared")
include(":edgeCase_jvmLib")
include(":edgeCase_jvmPlusAndroid")
include(":edgeCase_kmpSinglePlatform")
include(":edgeCase_noSources")
include(":edgeCase_wasmJsPlusWasmWasi")