/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.jetbrains.kotlintoolchain.kmp.sample"
version = "1.0.0"

// This module has no sources at all, it only re-exports its dependencies.
// Note: `publishToMavenLocal` fails for this module with KGP: `generateMetadataFileForLinuxX64Publication` expects
// a klib that was never compiled (`compileKotlinLinuxX64` is NO-SOURCE). Only the root and JVM publications can be
// generated here (`publishKotlinMultiplatformPublicationToMavenLocal`, `publishJvmPublicationToMavenLocal`), and their
// .module files are the reference files used by GradleMetadataGenerationTest.
kotlin {
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "noSources", version.toString())

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
