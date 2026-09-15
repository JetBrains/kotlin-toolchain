/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToDownloadChecksums
import org.jetbrains.amper.test.dr.toMavenNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/**
 * A Kotlin Multiplatform library may be published without a sources JAR
 * (this is what `settings.publishing.publishSources: false`, the default, produces).
 *
 * Such a library declares no sources variant in its Gradle module metadata at all, so dependency resolution
 * falls back to guessing the sources coordinates (see `MavenDependencyImpl.getAutoAddedSourcesDependencyFile`).
 * The guessed artifact simply doesn't exist, and that must stay harmless: sources are optional.
 *
 * See KTC-5830.
 */
class KmpLibraryWithoutSourcesTest : BaseDRTest() {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `KMP library published without a sources jar is resolved with sources requested`() = runDrTest {
        val root = resolveLibraryPublishedInMavenLocal(declaresSourcesVariant = false)

        // Only the repackaged source set klibs are expected: the library has no sources to offer.
        // No message above INFO either: an absent auto-added sources JAR is not a problem to report.
        downloadAndAssertFiles(
            [
                "kmp-lib-no-sources-commonMain-1.0.klib",
                "kmp-lib-no-sources-iosMain-1.0.klib",
            ],
            root,
            withSources = true,
            verifyMessages = true,
        )
    }

    /**
     * A publication that declares a sources variant but doesn't ship the JAR is broken, and it is reported as such.
     * Resolution of everything else must still succeed, though.
     *
     * The missing sources JAR is reported back among the files on purpose: files that failed to resolve are kept
     * so that their diagnostics don't get swallowed, see [MavenDependencyImpl.files].
     */
    @Test
    fun `KMP library declaring a sources variant with a missing jar is resolved with sources requested`() = runDrTest {
        val root = resolveLibraryPublishedInMavenLocal(declaresSourcesVariant = true)

        downloadAndAssertFiles(
            [
                "kmp-lib-no-sources-1.0-sources.jar",
                "kmp-lib-no-sources-commonMain-1.0.klib",
                "kmp-lib-no-sources-iosMain-1.0.klib",
            ],
            root,
            withSources = true,
            verifyMessages = false
        )
        assertTheOnlyNonInfoMessage<UnableToDownloadChecksums>(root, Severity.ERROR)
    }

    private suspend fun resolveLibraryPublishedInMavenLocal(
        declaresSourcesVariant: Boolean,
    ): DependencyNodeHolderWithContext {
        val testCacheRoot = (tmpDir / UUID.randomUUID().toString().substring(0, 8)).createDirectories()
        val mavenLocalPath = MavenLocalRepository(testCacheRoot / ".m2.test")


        val mavenLocal = publishKmpLibraryWithoutSources(mavenLocalPath, declaresSourcesVariant)

        val context = context(
            platform = setOf(ResolutionPlatform.IOS_ARM64, ResolutionPlatform.IOS_SIMULATOR_ARM64),
            repositories = [MAVEN_LOCAL],
            cacheBuilder = mavenLocalCacheBuilder(testCacheRoot, mavenLocal),
        )
        val root = RootDependencyNodeWithContext(
            children = ["org.jetbrains.dr.test:kmp-lib-no-sources:1.0".toMavenNode(context)],
            templateContext = context,
        )

        return doTest(
            root,
            expected = """
                root
                ╰─── org.jetbrains.dr.test:kmp-lib-no-sources:1.0
            """.trimIndent(),
        )
    }

    /**
     * Amper-like configuration of repositories with a custom mavenLocal location.
     */
    private fun mavenLocalCacheBuilder(
        cacheRoot: Path,
        customMavenLocalRepository: MavenLocalRepository,
    ): FileCacheBuilder.() -> Unit = {
        amperCache = cacheRoot
        localRepository = MavenLocalRepository(cacheRoot / ".m2.cache.test")
        readOnlyExternalRepositories = []
        mavenLocalRepository = customMavenLocalRepository
    }

    /**
     * Installs a KMP library into a [mavenLocal] repository:
     * an all-metadata JAR, the root module metadata with no sources variant, per-target modules, and no `-sources.jar` anywhere.
     *
     * With [declaresSourcesVariant], the root module metadata additionally declares a sources variant while
     * the sources JAR still isn't there, which mimics a publication broken in a slightly different way.
     */
    private fun publishKmpLibraryWithoutSources(
        mavenLocal: MavenLocalRepository,
        declaresSourcesVariant: Boolean,
    ): MavenLocalRepository {

        val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-no-sources/1.0").createDirectories()

        (rootDir / "kmp-lib-no-sources-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
        (rootDir / "kmp-lib-no-sources-1.0.module").writeText(rootModuleMetadata(declaresSourcesVariant))
        writeAllMetadataJar(rootDir / "kmp-lib-no-sources-1.0.jar")

        ["iosarm64", "iossimulatorarm64"]
            .forEach { target ->
                val targetDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-no-sources-$target/1.0")
                    .createDirectories()
                (targetDir / "kmp-lib-no-sources-$target-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
                (targetDir / "kmp-lib-no-sources-$target-1.0.module").writeText(leafModuleMetadata(target))
                (targetDir / "kmp-lib-no-sources-$target-1.0.klib").writeText("fake klib of $target")
            }

        return mavenLocal
    }

    /**
     * Writes the all-metadata JAR: the source set descriptor plus one directory per published source set.
     */
    private fun writeAllMetadataJar(target: Path) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.writeEntry("META-INF/kotlin-project-structure-metadata.json", PROJECT_STRUCTURE_METADATA)
            ["commonMain", "iosMain"]
                .forEach { sourceSet ->
                    zip.putNextEntry(ZipEntry("$sourceSet/"))
                    zip.closeEntry()
                    zip.writeEntry("$sourceSet/manifest", "fake $sourceSet metadata klib content")
                }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private companion object {

        /** The marker that makes dependency resolution prefer Gradle module metadata over this POM. */
        const val GRADLE_METADATA_MARKER_POM = "do_not_remove: published-with-gradle-metadata"

        /** Declares a sources JAR that is deliberately never written to the repository. */
        val METADATA_SOURCES_ELEMENTS_VARIANT = """
            {
              "name": "metadataSourcesElements",
              "attributes": {
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "sources",
                "org.gradle.jvm.environment": "non-jvm",
                "org.gradle.usage": "kotlin-runtime",
                "org.jetbrains.kotlin.platform.type": "common"
              },
              "files": [
                {
                  "name": "kmp-lib-no-sources-1.0-sources.jar",
                  "url": "kmp-lib-no-sources-1.0-sources.jar",
                  "size": -1,
                  "sha1": "da39a3ee5e6b4b0d3255bfef95601890afd80709"
                }
              ]
            }
        """.trimIndent()

        fun rootModuleMetadata(declaresSourcesVariant: Boolean) = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "org.jetbrains.dr.test",
                "module": "kmp-lib-no-sources",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
              "variants": [
                ${if (declaresSourcesVariant) "$METADATA_SOURCES_ELEMENTS_VARIANT," else ""}
                {
                  "name": "metadataApiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-metadata",
                    "org.jetbrains.kotlin.platform.type": "common"
                  },
                  "files": [
                    {
                      "name": "kmp-lib-no-sources-metadata-1.0.jar",
                      "url": "kmp-lib-no-sources-1.0.jar",
                      "size": -1
                    }
                  ]
                },
                {
                  "name": "iosArm64ApiElements-published",
                  "attributes": {
                    "artifactType": "org.jetbrains.kotlin.klib",
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "ios_arm64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "available-at": {
                    "url": "../../kmp-lib-no-sources-iosarm64/1.0/kmp-lib-no-sources-iosarm64-1.0.module",
                    "group": "org.jetbrains.dr.test",
                    "module": "kmp-lib-no-sources-iosarm64",
                    "version": "1.0"
                  }
                },
                {
                  "name": "iosSimulatorArm64ApiElements-published",
                  "attributes": {
                    "artifactType": "org.jetbrains.kotlin.klib",
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "ios_simulator_arm64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "available-at": {
                    "url": "../../kmp-lib-no-sources-iossimulatorarm64/1.0/kmp-lib-no-sources-iossimulatorarm64-1.0.module",
                    "group": "org.jetbrains.dr.test",
                    "module": "kmp-lib-no-sources-iossimulatorarm64",
                    "version": "1.0"
                  }
                }
              ]
            }
        """.trimIndent()

        fun leafModuleMetadata(target: String) = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "org.jetbrains.dr.test",
                "module": "kmp-lib-no-sources-$target",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
              "variants": [
                {
                  "name": "apiElements",
                  "attributes": {
                    "artifactType": "org.jetbrains.kotlin.klib",
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "${target.toNativeTargetAttribute()}",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "files": [
                    {
                      "name": "kmp-lib-no-sources-$target-1.0.klib",
                      "url": "kmp-lib-no-sources-$target-1.0.klib",
                      "size": -1
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        private fun String.toNativeTargetAttribute() = when (this) {
            "iosarm64" -> "ios_arm64"
            "iossimulatorarm64" -> "ios_simulator_arm64"
            else -> error("Unexpected target $this")
        }

        val PROJECT_STRUCTURE_METADATA = """
            {
              "projectStructure": {
                "formatVersion": "0.3.3",
                "isPublishedAsRoot": "true",
                "variants": [
                  {
                    "name": "iosArm64ApiElements",
                    "sourceSet": [ "iosMain", "commonMain" ]
                  },
                  {
                    "name": "iosSimulatorArm64ApiElements",
                    "sourceSet": [ "iosMain", "commonMain" ]
                  }
                ],
                "sourceSets": [
                  {
                    "name": "commonMain",
                    "dependsOn": [],
                    "moduleDependency": [],
                    "binaryLayout": "klib"
                  },
                  {
                    "name": "iosMain",
                    "dependsOn": [ "commonMain" ],
                    "moduleDependency": [],
                    "binaryLayout": "klib"
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
