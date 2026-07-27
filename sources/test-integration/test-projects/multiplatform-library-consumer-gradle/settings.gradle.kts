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
        // Only the locally published sample libraries come from ~/.m2. Without this filter, incomplete local copies
        // of common libraries (e.g. a kotlin-test that has a POM but no Gradle module metadata) shadow Maven Central
        // and break variant/capability matching.
        mavenLocal {
            content {
                includeGroup("org.jetbrains.kotlintoolchain.kmp.sample")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "multiplatform-library-consumer-gradle"
include(":jvmLibConsumer")
include(":jvmPlusAndroidConsumer")
include(":kmpJvmLibConsumer")
//include(":libraryCinteropConsumer")
//include(":libraryConsumer")
include(":nativePlatformConsumer")
include(":wasmJsPlusWasmWasiConsumer")