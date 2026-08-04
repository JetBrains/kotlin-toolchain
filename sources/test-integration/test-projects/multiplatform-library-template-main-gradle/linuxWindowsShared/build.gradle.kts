plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.sample"
version = "1.0.0"

kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation(project(":nativeShared"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}


mavenPublishing {
    coordinates(group.toString(), "linuxWindowsShared", version.toString())

    pom {
        name = "linuxWindowsShared"
        description = "A shared platform library."
        inceptionYear = "2024"
        url = "https://github.com/kotlin/multiplatform-library-template/"
        licenses {
            license {
                name = "native-platform"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "native-platform"
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
