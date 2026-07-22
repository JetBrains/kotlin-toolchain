plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.test.sample"
version = "1.0.0"

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            // Regular KMP library
//            implementation(libs.kotlinx.coroutines.core)
            // KMP library with applicable "-cinterop" source sets
            implementation(libs.crypto.rand)
            api(project(":library"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}


mavenPublishing {
//    publishToMavenCentral()

//    signAllPublications()

    coordinates(group.toString(), "nativeShared", version.toString())

    pom {
        name = "My native shared library"
        description = "A shared library."
        inceptionYear = "2024"
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
