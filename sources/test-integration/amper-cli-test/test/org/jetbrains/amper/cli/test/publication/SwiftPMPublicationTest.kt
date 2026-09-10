/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertGradleMetadataEquals
import org.jetbrains.amper.cli.test.utils.assertSwiftPMMetadataEquals
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.swiftpm.SwiftPMDependencies
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    @Tag("gold-file")
    fun `swiftpm metadata is published in a dedicated variant`(testInfo: TestInfo) = runSlowTest {
        val projectDir = testProject("swiftpm-publication")
        val result = runCli(
            projectDir = projectDir,
            "task", ":swiftpm-publication:prepareMavenPublishables",
        )

        val publishablesDir = result.getTaskOutputPath(":swiftpm-publication:prepareMavenPublishables")

        assertGradleMetadataEquals(
            expectedFileNameSuffix = "module.json",
            actualFile = publishablesDir / "swiftPMPublication-1.0.0.module",
            testInfo = testInfo,
        )
        val metadataFile = publishablesDir / "swiftPMPublication-1.0.0-swiftpm-metadata.json"
        assertSwiftPMMetadataEquals(
            expectedFileNameSuffix = "swiftpm-metadata.json",
            actualFile = metadataFile,
            projectDir = projectDir,
            testInfo = testInfo,
        )
        assertDeploymentVersionsAreExplicitNulls(metadataFile)
    }

    private fun assertDeploymentVersionsAreExplicitNulls(metadataFile: Path) {
        val rawMetadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
        val deploymentVersionKeys = listOf("ios", "macos", "watchos", "tvos").map { "${it}DeploymentVersion" }
        assertEquals(
            deploymentVersionKeys.associateWith { JsonNull },
            deploymentVersionKeys.associateWith { rawMetadata[it] },
            "The deployment version keys must be present and null in $metadataFile:\n$rawMetadata",
        )
    }

    /**
     * This test checks that a consumer of the published library correctly resolves SwiftPM packages declared by the library.
     *
     * The `gradle dependency` test of `SwiftPMResolutionTest` covers the same consumption path against a handwritten
     * KGP publication. This one covers it against a real Kotlin Toolchain publication.
     */
    @Test
    @MacOnly
    fun `published swiftpm metadata is consumed by a library consumer`() = runSlowTest {
        val mavenLocalRepository = tempRoot / "m2"
        runCliWithCustomM2(
            projectDir = testProject("swiftpm-publication"),
            mavenLocalRepository = mavenLocalRepository,
            "publish", "mavenLocal",
        )

        val dumpPath = tempRoot / "consumer-swiftpm-dependencies.json"
        runCli(
            projectDir = testProject("swiftpm-publication-consumer"),
            "task", ":consumer:dumpSwiftPMDependencyResolution",
            configureEnvironment = { put ("SWIFTPM_RESOLUTION_DUMP_PATH",  dumpPath.pathString) },
            amperJvmArgs = ["-Dmaven.repo.local=\"${mavenLocalRepository.absolutePathString()}\""],
        )

        val resolved = swiftPMJson.decodeFromString<SwiftPMDependencies>(dumpPath.readText())
        assertEquals(
            emptySet(),
            resolved.directSwiftPMDependencies,
            "The consumer declares no SwiftPM package of its own, they all come from the published library",
        )

        val [identifier, metadata] = assertNotNull(
            resolved.transitiveSwiftPMDependencies.metadataByDependencyIdentifier.entries.singleOrNull(),
            "The packages of the published library must reach the consumer, but the transitive metadata is " +
                    "${resolved.transitiveSwiftPMDependencies.metadataByDependencyIdentifier}",
        )
        assertEquals(
            "org_jetbrains_kotlintoolchain_swiftpm_sample_swiftPMPublication_1_0_0",
            identifier.identifier,
            "The metadata is keyed by the sanitized Maven coordinates of the library that published it",
        )
        assertEquals(setOf("ios_arm64", "macos_arm64"), metadata.konanTargets)
        assertEquals(
            mapOf(
                "commonPackage" to listOf("CommonProduct" to null),
                "iosOnlyPackage" to listOf("IosOnlyProduct" to listOf(SwiftPMDependency.Platform.iOS)),
            ),
            metadata.dependencies.associate { dependency ->
                val local = dependency as SwiftPMDependency.Local
                local.packageName to local.products.map { it.name to it.platformConstraints }
            },
            "The consumer must see the very same packages and platform constraints that the library published",
        )
    }
}
