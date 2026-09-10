/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.SwiftPMImportMetadata
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.gradle.module.metadata.format.Module
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A library that declares direct SwiftPM dependencies must publish them, so that its consumers know which SwiftPM
 * packages they have to fetch and link. Just like KGP, we publish them as a JSON file exposed in a dedicated
 * `swiftPMDependenciesMetadataElements` variant of the root publication.
 *
 * This test is Mac-only because publishing an Apple library requires compiling its klibs and cinterops, which in turn
 * requires the SwiftPM import to run (and thus Xcode). The test project only declares local Swift packages, so no
 * network access is needed.
 */
@Tag("cli-test-group-publication")
class SwiftPMPublicationTest : CliTestBase() {

    @Test
    @MacOnly
    fun `swiftpm metadata is published in a dedicated variant`() = runSlowTest {
        val projectDir = testProject("swiftpm-publication")
        val result = runCli(
            projectDir = projectDir,
            "task", ":swiftpm-publication:prepareMavenPublishables",
        )

        val publishablesDir = result.getTaskOutputPath(":swiftpm-publication:prepareMavenPublishables")

        assertSwiftPMVariantIsExposed(publishablesDir)
        assertPublishedMetadataDescribesDeclaredPackages(publishablesDir, projectDir)
    }

    /**
     * Consumers discover the SwiftPM metadata through a dedicated variant of the root publication, so it must be there
     * with the attributes and the file URL that KGP consumers expect.
     */
    private fun assertSwiftPMVariantIsExposed(publishablesDir: Path) {
        val rootMetadata = gradleMetadataJson
            .decodeFromString<Module>((publishablesDir / "swiftPMPublication-1.0.0.module").readText())
        val swiftPMVariant = assertNotNull(
            rootMetadata.variants.singleOrNull { it.name == "swiftPMDependenciesMetadataElements" },
            "The root publication must expose the SwiftPM metadata, but its variants are " +
                    "${rootMetadata.variants.map { it.name }}",
        )
        assertEquals(
            mapOf("org.gradle.category" to "library", "org.gradle.usage" to "swiftPMDependenciesMetadata"),
            swiftPMVariant.attributes,
        )
        assertEquals(
            "swiftPMPublication-1.0.0-swiftpm-metadata.json",
            swiftPMVariant.files.single().url,
            "Consumers locate the SwiftPM metadata using the file URL declared in the variant",
        )
    }

    private fun assertPublishedMetadataDescribesDeclaredPackages(publishablesDir: Path, projectDir: Path) {
        val metadataFile = publishablesDir / "swiftPMPublication-1.0.0-swiftpm-metadata.json"
        val metadata = swiftPMJson.decodeFromString<SwiftPMImportMetadata>(metadataFile.readText())

        // Targets are named after KonanTarget, like in the KGP publication.
        assertEquals(setOf("ios_arm64", "macos_arm64"), metadata.konanTargets)

        // Deployment targets are only published when the library declares them, which Kotlin Toolchain cannot do yet.
        // Consumers raise their own minimum to the maximum of the published values, so publishing the defaults we use
        // when building would bump the minimum OS version of every consumer.
        assertNull(metadata.iosDeploymentVersion)
        assertNull(metadata.macosDeploymentVersion)
        assertNull(metadata.watchosDeploymentVersion)
        assertNull(metadata.tvosDeploymentVersion)

        // The nulls must be written out explicitly: these keys have no default in KGP's model, so kotlinx treats them
        // as required, and omitting them makes KGP consumers fail to read the file at all.
        val rawMetadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
        val deploymentVersionKeys = listOf("ios", "macos", "watchos", "tvos").map { "${it}DeploymentVersion" }
        assertEquals(
            deploymentVersionKeys.associateWith { JsonNull },
            deploymentVersionKeys.associateWith { rawMetadata[it] },
            "The deployment version keys must be present and null in $metadataFile:\n$rawMetadata",
        )

        // The package declared in the common fragment applies to all Apple targets of this module, so its product is
        // unconstrained, while the one declared in the 'ios' fragment carries the iOS platform constraint.
        assertEquals(
            mapOf(
                "commonPackage" to listOf("CommonProduct" to null),
                "iosOnlyPackage" to listOf("IosOnlyProduct" to listOf(SwiftPMDependency.Platform.iOS)),
            ),
            metadata.dependencies.associate { dependency ->
                // Local packages are published as absolute paths, so we can only assert their location in the project.
                val local = dependency as SwiftPMDependency.Local
                local.absolutePath.toRealPath().relativeTo(projectDir.toRealPath()).toString() to
                        local.products.map { it.name to it.platformConstraints }
            },
        )
    }

    private val gradleMetadataJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
