/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.json.Json
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.test.assertEquals

/**
 * The SwiftPM metadata we publish is read back by the Kotlin Gradle Plugin, so its JSON shape must match the one KGP
 * writes and expects: the same type discriminators, and the deployment version keys present even when they are null
 * (they have no default in KGP's model, so KGP fails to read the file if they are missing).
 */
class SwiftPMPublicationJsonTest {

    @Test
    fun `published metadata has the json shape that KGP expects`() {
        val metadata = SwiftPMImportMetadata(
            konanTargets = setOf("ios_arm64", "macos_arm64"),
            iosDeploymentVersion = null,
            macosDeploymentVersion = null,
            watchosDeploymentVersion = null,
            tvosDeploymentVersion = null,
            isModulesDiscoveryEnabled = true,
            dependencies = setOf(
                SwiftPMDependency.Remote(
                    repository = SwiftPMDependency.Remote.Repository.Url("https://foo/bar/baz.git"),
                    version = SwiftPMDependency.Remote.Version.From("1.0.0"),
                    products = [SwiftPMDependency.Product("Baz", cinteropClangModules = [], platformConstraints = null)],
                    cinteropClangModules = [],
                    packageName = "baz",
                    traits = [],
                ),
                SwiftPMDependency.Local(
                    absolutePath = localPackagePath,
                    products = [
                        SwiftPMDependency.Product(
                            name = "IosOnly",
                            cinteropClangModules = [],
                            platformConstraints = [SwiftPMDependency.Platform.iOS],
                        )
                    ],
                    cinteropClangModules = [],
                    packageName = "iosOnly",
                    traits = [],
                ),
            ),
        )

        assertEquals(
            Json.parseToJsonElement(expectedJson),
            Json.parseToJsonElement(swiftPMPublicationJson.encodeToString(metadata)),
        )
    }

    private val localPackagePath: SerializablePath = Path("iosOnlyPackage").toAbsolutePath()

    // The local path is injected as a JSON string so that Windows path separators are properly escaped.
    private val expectedJson: String
        get() = expectedJsonTemplate.replace("<LOCAL_PACKAGE_PATH>", Json.encodeToString(localPackagePath.pathString))

    private val expectedJsonTemplate = """
        {
          "konanTargets": [ "ios_arm64", "macos_arm64" ],
          "iosDeploymentVersion": null,
          "macosDeploymentVersion": null,
          "watchosDeploymentVersion": null,
          "tvosDeploymentVersion": null,
          "isModulesDiscoveryEnabled": true,
          "dependencies": [
            {
              "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote",
              "repository": {
                "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Url",
                "value": "https://foo/bar/baz.git"
              },
              "version": {
                "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.From",
                "value": "1.0.0"
              },
              "products": [
                { "name": "Baz", "cinteropClangModules": [], "platformConstraints": null }
              ],
              "cinteropClangModules": [],
              "packageName": "baz",
              "traits": []
            },
            {
              "type": "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Local",
              "absolutePath": <LOCAL_PACKAGE_PATH>,
              "products": [
                { "name": "IosOnly", "cinteropClangModules": [], "platformConstraints": [ "iOS" ] }
              ],
              "cinteropClangModules": [],
              "packageName": "iosOnly",
              "traits": []
            }
          ]
        }
    """.trimIndent()
}
