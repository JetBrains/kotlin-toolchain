plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.sample"
version = "1.0.0"

kotlin {
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    kotlin {
        listOf(
            linuxX64(),
            linuxArm64(),
            macosArm64(),
            macosX64(),
        ).forEach {
            it.compilations.getByName("main").cinterops.create("libcurl") {
                defFile("libcurl.def")
                includeDirs(projectDir.resolve("headers"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // KMP library with applicable "-cinterop" source sets
            api(project(":libraryNested"))
        }
    }
}


mavenPublishing {
    coordinates(group.toString(), "libraryCinterop", version.toString())

    pom {
        name = "libraryCinterop"
        description = "A shared library with cinterop"
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
