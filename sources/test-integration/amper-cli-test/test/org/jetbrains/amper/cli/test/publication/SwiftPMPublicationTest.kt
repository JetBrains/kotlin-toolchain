/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.SwiftPMImportMetadata
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.gradle.module.metadata.format.Module
import org.junit.jupiter.api.Tag
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A library that declares direct SwiftPM dependencies must publish them, so that its consumers know which SwiftPM
 * packages they have to fetch and link. Just like KGP, we publish them as a JSON file exposed in a dedicated
 * `swiftPMDependenciesMetadataElements` variant of the root publication.
 *
 * These tests are Mac-only because publishing an Apple library requires compiling its klibs and cinterops, which in
 * turn requires the SwiftPM import to run (and thus Xcode).
 */
@Tag("cli-test-group-publication")
class SwiftPMPublicationTest : CliTestBase() {

    @Test
    @MacOnly
    fun `swiftpm metadata is published in a dedicated variant`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("swiftpm-publication"),
            "task", ":swiftpm-publication:prepareMavenPublishables",
        )

        val publishablesDir = result.getTaskOutputPath(":swiftpm-publication:prepareMavenPublishables")

        val rootMetadata = json.decodeFromString<Module>((publishablesDir / "swiftPMPublication-1.0.0.module").readText())
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

    @Test
    @MacOnly
    fun `published swiftpm metadata describes the declared packages`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("swiftpm-publication"),
            "task", ":swiftpm-publication:prepareMavenPublishables",
        )

        val metadataFile = result.getTaskOutputPath(":swiftpm-publication:prepareMavenPublishables") /
                "swiftPMPublication-1.0.0-swiftpm-metadata.json"
        val metadata = swiftPMJson.decodeFromString<SwiftPMImportMetadata>(metadataFile.readText())

        // Targets are named after KonanTarget, like in the KGP publication.
        assertEquals(setOf("ios_arm64", "ios_simulator_arm64", "macos_arm64"), metadata.konanTargets)

        // The package declared in the common fragment applies to all Apple targets of this module, so its product is
        // unconstrained, while the one declared in the 'ios' fragment carries the iOS platform constraint.
        assertEquals(
            mapOf(
                "https://foo/bar/baz.git" to listOf("Baz" to null),
                "https://foo/bar/ios-only.git" to listOf("IosOnly" to listOf(SwiftPMDependency.Platform.iOS)),
            ),
            metadata.dependencies.associate { dependency ->
                val remote = dependency as SwiftPMDependency.Remote
                remote.repository.value to remote.products.map { it.name to it.platformConstraints }
            },
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
