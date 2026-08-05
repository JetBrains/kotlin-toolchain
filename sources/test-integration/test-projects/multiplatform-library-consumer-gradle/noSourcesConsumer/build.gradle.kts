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
// Note on the POM packaging: from a regular Maven repository (remote or file-based), Gradle resolves this library
// through its Gradle module metadata, and the artifact-less linuxX64 variant is consumable whatever the POM says.
// It is `mavenLocal()` that is picky: it considers a module missing when the file implied by the POM packaging is
// absent from ~/.m2. The klib-less publication therefore declares `<packaging>pom</packaging>`, which is also what
// keeps it resolvable for plain Maven consumers, since those don't read Gradle module metadata.
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
