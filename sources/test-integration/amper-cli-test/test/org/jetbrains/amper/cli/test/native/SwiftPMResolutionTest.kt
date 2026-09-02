/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.native

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.swiftpm.SwiftPMDependencies
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.TransitiveSwiftPMMetadata
import org.jetbrains.amper.test.runTestWithMdc
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SwiftPMResolutionTest : CliTestBase() {

    @Test
    fun `direct swiftpm dependency`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/direct-swiftpm-dependency",
            "direct-swiftpm-dependency"
        )
        assertEquals(
            listOf(
                "https://foo/bar/baz.git",
            ),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            listOf(
                listOf(
                    "product" to null
                ),
            ),
            resolved.directSwiftPMDependencies.map {
                it.products.map {
                    it.name to it.platformConstraints
                }
            },
        )
        assertEquals(
            emptyMap(),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    @Test
    fun `scoped swiftpm dependency`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/scoped-dependency",
            "scoped-dependency"
        )
        assertEquals(
            listOf(
                "https://foo/bar/baz.git",
            ),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            listOf(
                listOf(
                    "unscoped" to null,
                    "scoped" to listOf(SwiftPMDependency.Platform.iOS),
                ),
            ),
            resolved.directSwiftPMDependencies.map {
                it.products.map {
                    it.name to it.platformConstraints
                }
            },
        )
        assertEquals(
            emptyMap(),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    @Test
    fun `disconnected swiftpm dependency`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/disconnected-swiftpm-dependency",
            "consumer"
        )
        assertEquals(
            emptyList(),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            emptyMap(),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    @Test
    fun `multiple transitive swiftpm dependencies`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/transitive-swiftpm-dependency",
            "consumer"
        )
        assertEquals(
            emptyList(),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            mapOf(
                "transitive" to listOf(
                    "https://foo/bar/first.git",
                    "https://foo/bar/second.git",
                ),
            ),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    @Test
    fun `single platform swiftpm dependency`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/single-platform-swiftpm-dependency",
            "consumer",
            prepareMavenLocal = { projectPath ->
                val repo = projectPath.resolve("repo")
                val component = repo.resolve("foo/kgp_swiftpm_publication/1.0")
                component.createDirectories()
                component.resolve("kgp_swiftpm_publication-1.0.pom").writeText("do_not_remove: published-with-gradle-metadata")
                component.resolve("kgp_swiftpm_publication-1.0.module").writeText(gradleMetadataWithSwiftPMMetadataVariant)
                component.resolve("kgp_swiftpm_publication-1.0-swiftpm-metadata.json").writeText(swiftPMMetadata)
                ZipOutputStream(component.resolve("kgp_swiftpm_publication-1.0.jar").outputStream()).use {
                    it.putNextEntry(ZipEntry("META-INF/kotlin-project-structure-metadata.json"))
                    it.write(psmStub.toByteArray())
                }
                repo
            }
        )
        assertEquals(
            listOf(
                "https://foo/bar/consumer.git",
            ),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            mapOf(
                "producer" to listOf(
                    "https://foo/bar/producer.git",
                ),
                "foo_kgp_swiftpm_publication_1_0" to listOf(
                    "https://foo/bar/baz.git"
                ),
            ),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    @Test
    fun `gradle dependency`() = runTestWithMdc {
        val resolved = resolveSwiftPMDependencies(
            "swiftpm-dr/gradle-dependency",
            "consumer",
            prepareMavenLocal = { projectPath ->
                val repo = projectPath.resolve("repo")
                val component = repo.resolve("foo/kgp_swiftpm_publication/1.0")
                component.createDirectories()
                component.resolve("kgp_swiftpm_publication-1.0.pom").writeText("do_not_remove: published-with-gradle-metadata")
                component.resolve("kgp_swiftpm_publication-1.0.module").writeText(gradleMetadataWithSwiftPMMetadataVariant)
                component.resolve("kgp_swiftpm_publication-1.0-swiftpm-metadata.json").writeText(swiftPMMetadata)
                ZipOutputStream(component.resolve("kgp_swiftpm_publication-1.0.jar").outputStream()).use {
                    it.putNextEntry(ZipEntry("META-INF/kotlin-project-structure-metadata.json"))
                    it.write(psmStub.toByteArray())
                }
                repo
            }
        )
        assertEquals(
            emptyList(),
            resolved.directSwiftPMDependencies.map {
                it.simplifiedView()
            },
        )
        assertEquals(
            mapOf(
                "foo_kgp_swiftpm_publication_1_0" to listOf(
                    "https://foo/bar/baz.git",
                ),
            ),
            resolved.transitiveSwiftPMDependencies.simplifiedView(),
        )
    }

    private fun TransitiveSwiftPMMetadata.simplifiedView(): Map<String, List<String>> = metadataByDependencyIdentifier.map {
        it.key.identifier to it.value.dependencies.map { it.simplifiedView() }
    }.toMap()

    private fun SwiftPMDependency.simplifiedView() = ((this as SwiftPMDependency.Remote).repository as SwiftPMDependency.Remote.Repository.Url).value

    private suspend fun resolveSwiftPMDependencies(
        project: String,
        moduleName: String,
        prepareMavenLocal: ((Path) -> Path)? = null
    ): SwiftPMDependencies {
        val dumpPath = tempRoot.resolve("dump.json")
        val projectPath = testProject(project)
        val mavenLocal = prepareMavenLocal?.invoke(projectPath)?.let {
            listOf("\"-Dmaven.repo.local=${it.pathString}\"")
        } ?: emptyList()
        runCli(
            projectDir = projectPath,
            "task", ":${moduleName}:dumpSwiftPMDependencyResolution",
            environment = mapOf(
                "SWIFTPM_RESOLUTION_DUMP_PATH" to dumpPath.pathString,
            ),
            amperJvmArgs = mavenLocal,
        )
        return dumpPath.inputStream().use {
            @OptIn(ExperimentalSerializationApi::class)
            json.decodeFromStream(it)
        }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
            allowStructuredMapKeys = true
        }

        const val gradleMetadataWithSwiftPMMetadataVariant = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "foo",
                "module": "kgp_swiftpm_publication",
                "version": "1.0"
              },
              "createdBy": {
                "gradle": {
                  "version": "9.4.0"
                }
              },
              "variants": [
                {
                  "name": "metadataApiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-metadata",
                    "org.jetbrains.kotlin.platform.type": "common"
                  },
                  "dependencies": [],
                  "files": [
                    {
                      "name": "kgp_swiftpm_publication-metadata-1.0.jar",
                      "url": "kgp_swiftpm_publication-1.0.jar"
                    }
                  ]
                },
                {
                  "name": "swiftPMDependenciesMetadataElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "swiftPMDependenciesMetadata"
                  },
                  "files": [
                    {
                      "name": "swiftPMDependenciesMetadata",
                      "url": "kgp_swiftpm_publication-1.0-swiftpm-metadata.json"
                    }
                  ]
                },
                {
                  "name": "iosArm64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "ios_arm64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "files": []
                },
                {
                  "name": "iosSimulatorArm64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "ios_simulator_arm64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "files": []
                },
                {
                  "name": "iosX64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "ios_x64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "files": []
                }
              ]
            }
        """

        const val swiftPMMetadata = """
            {
              "konanTargets" : [ "ios_arm64", "ios_simulator_arm64", "ios_x64" ],
              "iosDeploymentVersion" : null,
              "macosDeploymentVersion" : null,
              "watchosDeploymentVersion" : null,
              "tvosDeploymentVersion" : null,
              "isModulesDiscoveryEnabled" : true,
              "dependencies" : [ {
                "type" : "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote",
                "repository" : {
                  "type" : "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Url",
                  "value" : "https://foo/bar/baz.git"
                },
                "version" : {
                  "type" : "org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.From",
                  "value" : "1.0.0"
                },
                "products" : [ {
                  "name" : "SwiftPMImport",
                  "cinteropClangModules" : [ ],
                  "platformConstraints" : null
                } ],
                "cinteropClangModules" : [ {
                  "name" : "SwiftPMImport",
                  "platformConstraints" : null
                } ],
                "packageName" : "SwiftPMImport",
                "traits" : [ ]
              } ]
            }
        """

        const val psmStub = """
            {
              "projectStructure": {
                "formatVersion": "0.3.3",
                "isPublishedAsRoot": "true",
                "variants": [],
                "sourceSets": []
              }
            }
        """
    }
}