pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "multiplatform-library-template"
include(":library")
include(":libraryNested")
include(":nativeShared")
include(":nativePlatform")
include(":linuxWindowsShared")
include(":nonNativeShared")
include(":edgeCase_jvmLib")
include(":edgeCase_jvmPlusAndroid")
include(":edgeCase_kmpSinglePlatform")
include(":edgeCase_wasmJsPlusWasmWasi")