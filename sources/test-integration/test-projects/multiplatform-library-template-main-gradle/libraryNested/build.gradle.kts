plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.test.sample"
version = "1.0.0"

kotlin {
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()
    jvm()
    android {
        namespace = "org.jetbrains.kotlintoolchain.kmp.test.sample.libraryNested"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

    kotlin {
        listOf(
            linuxX64(),
            linuxArm64(),
            macosArm64(),
            macosX64(),
        ).forEach {
            it.compilations.getByName("main").cinterops.create("custom") {
                defFile("custom.def")
                includeDirs(rootDir.resolve("include"))
            }
            it.compilations.getByName("main").cinterops.create("custom2") {
                defFile("custom2.def")
                includeDirs(rootDir.resolve("include"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Regular KMP library
            implementation(libs.kotlinx.coroutines.core)
            // KMP library with applicable "-cinterop" source sets
            implementation(project(":library"))
            implementation(project.dependencies.platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
        }
    }
}


mavenPublishing {
    coordinates(group.toString(), "libraryNested", version.toString())

    pom {
        name = "libraryNested"
        description = "A shared nested library."
        inceptionYear = "2026"
        url = "https://github.com/kotlin/multiplatform-library-template/"
        licenses {
            license {
                name = "XXX"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "XXX"
                name = "YYY"
                url = "ZZZ"
            }
        }
        scm {
            url = "XXX"
            connection = "YYY"
            developerConnection = "ZZZ"
        }
    }
}
