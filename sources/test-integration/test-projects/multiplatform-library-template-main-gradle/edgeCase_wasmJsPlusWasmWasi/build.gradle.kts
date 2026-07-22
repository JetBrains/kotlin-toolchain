//import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.test.sample"
version = "1.0.0"

kotlin {
    wasmJs()
    wasmWasi()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

            //put your jvm-only dependencies here
            implementation("org.tinylog:tinylog-api-kotlin:2.7.0")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "wasmJsPlusWasmWasi", version.toString())

    pom {
        name = "library"
        description = "A library."
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
