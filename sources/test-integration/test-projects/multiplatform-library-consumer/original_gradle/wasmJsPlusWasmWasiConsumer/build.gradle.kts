//import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.consumer.gradle_sample"
version = "1.0.0"

kotlin {
    wasmJs()
    wasmWasi()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation("org.jetbrains.kotlintoolchain.kmp.sample:wasmJsPlusWasmWasi:1.0.0")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "wasmJsPlusWasmWasiConsumer", version.toString())

    pom {
        name = "wasmJsPlusWasmWasiConsumer"
        description = "A library."
        inceptionYear = "2024"
        url = "https://github.com/kotlin/multiplatform-library-consumer-gradle/"
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
