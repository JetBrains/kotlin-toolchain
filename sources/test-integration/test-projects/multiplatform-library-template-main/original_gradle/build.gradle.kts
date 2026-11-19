plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

// The '*.<something>.original_gradle.json' reference files (see test-integration/amper-cli-test/testResources) keep
// the metadata that this Gradle project generates. They are not asserted, they are only the reference of what KGP
// produces for the same modules as the Kotlin Toolchain project one directory up, so they have to be regenerated
// whenever this project (or its Gradle/Kotlin versions) changes. The tasks below automate that: they copy the
// metadata files verbatim from the build outputs of this project, leaving the review and the commit to the user.
val kotlinToolchainTestResourcesDir = rootDir.parentFile.parentFile.parentFile.resolve("amper-cli-test/testResources")

tasks.register<Copy>("generateModuleMetadataGoldFiles") {
    group = "gold file generation"
    description = "Copies the Gradle module metadata of the published modules to their " +
            "'*.module.original_gradle.json' reference files."

    // Copies the module.json of a publication of a module to [goldFileName]. The publication names are the ones
    // KGP generates: 'kotlinMultiplatform' for the root publication of a KMP library, the target name for its
    // platform publications, and 'maven' for the single publication of a plain JVM project.
    fun gold(modulePath: String, publicationName: String, goldFileName: String) {
        val module = project(modulePath)
        dependsOn(module.tasks.named("generateMetadataFileFor${publicationName.replaceFirstChar { it.uppercase() }}Publication"))
        from(module.layout.buildDirectory.file("publications/$publicationName/module.json")) {
            rename { goldFileName }
        }
    }

    gold(":edgeCase_jvmLib", "maven", "pure_jvm_library.module.original_gradle.json")
    gold(":edgeCase_kmpSinglePlatform", "kotlinMultiplatform", "kmp_jvm_library.module.original_gradle.json")
    gold(":edgeCase_kmpSinglePlatform", "jvm", "kmp_jvm_library.jvm.module.original_gradle.json")
    gold(":edgeCase_jvmPlusAndroid", "kotlinMultiplatform", "jvm_plus_android.module.original_gradle.json")
    gold(":edgeCase_jvmPlusAndroid", "jvm", "jvm_plus_android.jvm.module.original_gradle.json")
    gold(":edgeCase_jvmPlusAndroid", "android", "jvm_plus_android.android.module.original_gradle.json")
    gold(":edgeCase_wasmJsPlusWasmWasi", "kotlinMultiplatform", "wasmJs_plus_wasmWasi.module.original_gradle.json")
    gold(":edgeCase_wasmJsPlusWasmWasi", "wasmJs", "wasmJs_plus_wasmWasi.wasmJs.module.original_gradle.json")
    gold(":edgeCase_wasmJsPlusWasmWasi", "wasmWasi", "wasmJs_plus_wasmWasi.wasmWasi.module.original_gradle.json")
    gold(":libraryNested", "kotlinMultiplatform", "libraryNested_module.module.original_gradle.json")
    gold(":libraryNested", "linuxX64", "libraryNested_module.linuxX64.module.original_gradle.json")
    gold(":libraryCinterop", "kotlinMultiplatform", "libraryCinterop_module.module.original_gradle.json")
    gold(":libraryCinterop", "linuxX64", "libraryCinterop_module.linuxX64-module.original_gradle.json")
    gold(":libraryCinterop", "macosArm64", "libraryCinterop_module.macosArm64-module.original_gradle.json")
    // 'edgeCase_noSources' has no linuxX64 reference file, because KGP can't generate that publication (see
    // settings.gradle.kts).
    gold(":edgeCase_noSources", "kotlinMultiplatform", "kmp_library_without_sources.module.original_gradle.json")
    gold(":edgeCase_noSources", "jvm", "kmp_library_without_sources.jvm.module.original_gradle.json")

    into(kotlinToolchainTestResourcesDir.resolve("gradleMetadata"))
}

tasks.register<Copy>("generateKotlinToolingMetadataGoldFiles") {
    group = "gold file generation"
    description = "Copies the Kotlin tooling metadata of the multiplatform modules to their " +
            "'*.kotlin-tooling-metadata.original_gradle.json' reference files."

    fun gold(modulePath: String, goldFileName: String) {
        val module = project(modulePath)
        dependsOn(module.tasks.named("buildKotlinToolingMetadata"))
        from(module.layout.buildDirectory.file("kotlinToolingMetadata/kotlin-tooling-metadata.json")) {
            rename { goldFileName }
        }
    }

    gold(":edgeCase_kmpSinglePlatform", "kmp_single_platform.kotlin-tooling-metadata.original_gradle.json")
    gold(":edgeCase_jvmPlusAndroid", "jvm_plus_android.kotlin-tooling-metadata.original_gradle.json")
    gold(":edgeCase_wasmJsPlusWasmWasi", "wasmJs_plus_wasmWasi.kotlin-tooling-metadata.original_gradle.json")
    gold(":linuxWindowsShared", "native_only.kotlin-tooling-metadata.original_gradle.json")
    gold(":edgeCase_noSources", "jvm_plus_native.kotlin-tooling-metadata.original_gradle.json")

    into(kotlinToolchainTestResourcesDir.resolve("kotlinToolingMetadata"))
}

tasks.register<Copy>("generateProjectStructureMetadataGoldFiles") {
    group = "gold file generation"
    description = "Copies the Kotlin project structure metadata of the native modules to their " +
            "'*.kotlin-project-structure-metadata.original_gradle.json' reference files."

    fun gold(modulePath: String, goldFileName: String) {
        val module = project(modulePath)
        dependsOn(module.tasks.named("generateProjectStructureMetadata"))
        from(module.layout.buildDirectory.file("kotlinProjectStructureMetadata/kotlin-project-structure-metadata.json")) {
            rename { goldFileName }
        }
    }

    gold(":libraryCinterop",
        "assemble_kotlin_project_structure_descriptor_of_libraryCinterop_module.kotlin-project-structure-metadata.original_gradle.json")
    gold(":libraryNested",
        "assemble_kotlin_project_structure_descriptor_of_libraryNested_module.kotlin-project-structure-metadata.original_gradle.json")

    into(kotlinToolchainTestResourcesDir.resolve("metadata"))
}

tasks.register("generateOriginalGradleGoldFiles") {
    group = "gold file generation"
    description = "Regenerates all the '*.original_gradle.json' reference files."
    dependsOn(
        tasks.named("generateModuleMetadataGoldFiles"),
        tasks.named("generateKotlinToolingMetadataGoldFiles"),
        tasks.named("generateProjectStructureMetadataGoldFiles"),
    )
}
