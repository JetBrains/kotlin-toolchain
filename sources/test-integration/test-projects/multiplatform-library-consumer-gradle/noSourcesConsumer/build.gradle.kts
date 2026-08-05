/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.consumer.gradle_sample"
version = "1.0.0"

// Consumes the 'noSources' library, which has no sources at all, and thus no klib for its native platforms.
// The dependencies re-exported by that library must still be available here.
//
// Note: building this module against the library published to ~/.m2 fails with
// "Could not find org.jetbrains.kotlintoolchain.kmp.sample:noSources-linuxx64:1.0.0".
// This is a limitation of Gradle's `mavenLocal()` repository, not of the publication: maven-local considers a module
// missing when the artifact declared by the POM packaging (`klib` here) is absent from the local repository, and the
// source-less module has no klib to publish. Consuming the very same publication from a regular Maven repository
// (`maven { url = ... }`) works: all compilations, including `compileKotlinLinuxX64`, succeed with the artifact-less
// linuxX64 variant.
kotlin {
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlintoolchain.kmp.sample:noSources:1.0.0")
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "noSourcesConsumer", version.toString())

    pom {
        name = "noSourcesConsumer"
        description = "A source-less library consumer."
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
