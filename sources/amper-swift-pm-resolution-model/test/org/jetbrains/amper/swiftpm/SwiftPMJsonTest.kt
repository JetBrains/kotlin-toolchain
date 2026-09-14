/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [swiftPMJson] is used for both reading and writing the SwiftPM metadata that we exchange with KGP, so it has to be
 * strict enough for KGP to read what we write and lenient enough to read what KGP writes.
 */
class SwiftPMJsonTest {

    private val metadataWithoutDeploymentVersions = SwiftPMImportMetadata(
        konanTargets = ["ios_arm64"],
        iosDeploymentVersion = null,
        macosDeploymentVersion = null,
        watchosDeploymentVersion = null,
        tvosDeploymentVersion = null,
        isModulesDiscoveryEnabled = false,
        dependencies = [],
    )

    @Test
    fun `null deployment versions are written explicitly`() {
        // These keys have no default in KGP's model, so kotlinx treats them as required. Omitting them makes KGP
        // consumers fail with a MissingFieldException instead of reading a null.
        val encoded = Json.parseToJsonElement(swiftPMJson.encodeToString(metadataWithoutDeploymentVersions)).jsonObject

        assertEquals(
            deploymentVersionKeys.associateWith { JsonNull.toString() },
            deploymentVersionKeys.associateWith { encoded[it]?.toString() ?: "<absent>" },
            "The deployment version keys must all be present and null in $encoded",
        )
    }

    @Test
    fun `missing deployment versions are read as null`() {
        val metadata = swiftPMJson.decodeFromString<SwiftPMImportMetadata>(metadataJsonWithoutOptionalKeys)

        assertNull(metadata.iosDeploymentVersion)
        assertNull(metadata.macosDeploymentVersion)
        assertNull(metadata.watchosDeploymentVersion)
        assertNull(metadata.tvosDeploymentVersion)
    }

    @Test
    fun `missing product platform constraints are read as null`() {
        val metadata = swiftPMJson.decodeFromString<SwiftPMImportMetadata>(metadataJsonWithoutOptionalKeys)

        val dependency = metadata.dependencies.single() as SwiftPMDependency.Remote
        assertNull(dependency.products.single().platformConstraints)
    }

    @Test
    fun `metadata survives a round trip`() {
        val metadata = SwiftPMImportMetadata(
            konanTargets = ["ios_arm64", "macos_arm64"],
            iosDeploymentVersion = "15.0",
            macosDeploymentVersion = null,
            watchosDeploymentVersion = null,
            tvosDeploymentVersion = null,
            isModulesDiscoveryEnabled = true,
            dependencies = [
                SwiftPMDependency.Remote(
                    repository = SwiftPMDependency.Remote.Repository.Url("https://foo/bar.git"),
                    version = SwiftPMDependency.Remote.Version.From("1.0"),
                    products = [
                        SwiftPMDependency.Product(
                            name = "Bar",
                            cinteropClangModules = ["BarModule"],
                            platformConstraints = [SwiftPMDependency.Platform.iOS],
                        ),
                    ],
                    cinteropClangModules = [SwiftPMDependency.CinteropClangModule("BarModule")],
                    packageName = "bar",
                    traits = ["SomeTrait"],
                ),
            ],
        )

        assertEquals(metadata, swiftPMJson.decodeFromString(swiftPMJson.encodeToString(metadata)))
    }

    companion object {
        private val deploymentVersionKeys = ["ios", "macos", "watchos", "tvos"].map { "${it}DeploymentVersion" }

        /**
         * SwiftPM metadata in which every key that is optional in our model is absent, as an older or newer KGP could
         * legitimately produce.
         */
        private val metadataJsonWithoutOptionalKeys = """
            {
              "konanTargets": ["ios_arm64"],
              "isModulesDiscoveryEnabled": false,
              "dependencies": [
                {
                  "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote",
                  "repository": {
                    "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Url",
                    "value": "https://foo/bar.git"
                  },
                  "version": {
                    "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.From",
                    "value": "1.0"
                  },
                  "products": [{ "name": "Bar", "cinteropClangModules": [] }],
                  "cinteropClangModules": [],
                  "packageName": "bar",
                  "traits": []
                }
              ]
            }
        """.trimIndent()
    }
}
