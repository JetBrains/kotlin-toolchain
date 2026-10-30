/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics
import org.jetbrains.amper.dependency.resolution.diagnostics.PlatformsAreNotSupported
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToDownloadChecksums
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resolution of Kotlin Multiplatform libraries (for iOS platforms unless stated otherwise),
 * the libraries are published into a custom mavenLocal repository with hand-written metadata
 * to mimic publications that are hard to find in the wild.
 */
class KmpLibraryTest : BaseDRTest() {

    @TempDir
    lateinit var tmpDir: Path

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
    @Test
    fun `KMP library published without a sources jar is resolved with sources requested`() = runDrTest {
        val root = resolveLibraryWithoutSources(declaresSourcesVariant = false)

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
        val root = resolveLibraryWithoutSources(declaresSourcesVariant = true)

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

    /**
     * A Kotlin Multiplatform library might be unusable on the requested platforms for different reasons:
     * the variant of one platform is rejected because of the capabilities it declares,
     * while there is no variant for another platform at all.
     * Each platform should be reported with its own reason.
     */
    @Test
    fun `platform without variant is reported apart from platform blocked by capabilities`() = runDrTest {
        val errors = resolveLibraryWithUnsupportedCapabilities(publishesLinuxX64 = true)

        assertEquals(
            [
                "The library org.jetbrains.dr.test:kmp-lib-capabilities:1.0 cannot be used for iosArm64: " +
                        "its matching variants declare capabilities, which is not supported yet (KTC-5532)",
                "Platform iosSimulatorArm64 is not supported by the library org.jetbrains.dr.test:kmp-lib-capabilities:1.0\n" +
                        "Supported platforms: [iosArm64, linuxX64]",
            ],
            errors,
        )
    }

    /**
     * The only platform of the library is blocked by capabilities, it is still listed as supported by the library.
     */
    @Test
    fun `platform blocked by capabilities is listed as supported`() = runDrTest {
        val errors = resolveLibraryWithUnsupportedCapabilities(publishesLinuxX64 = false)

        assertEquals(
            [
                "The library org.jetbrains.dr.test:kmp-lib-capabilities:1.0 cannot be used for iosArm64: " +
                        "its matching variants declare capabilities, which is not supported yet (KTC-5532)",
                "Platform iosSimulatorArm64 is not supported by the library org.jetbrains.dr.test:kmp-lib-capabilities:1.0\n" +
                        "Supported platform: [iosArm64]",
            ],
            errors,
        )
    }

    /**
     * The only variant of the library for the platform is a `kotlin-cinterop` one, which can't be used.
     */
    @Test
    fun `platform with unusable variants is reported`() = runDrTest {
        val root = rootOfLibraryInMavenLocal(
            "org.jetbrains.dr.test:kmp-lib-cinterop-only:1.0",
            platforms = setOf(ResolutionPlatform.IOS_SIMULATOR_ARM64),
        ) { mavenLocal ->
            LibraryWithCinteropOnlyVariant.publish(mavenLocal)
        }
        doTest(root, verifyMessages = false)

        assertEquals(
            [
                "The library org.jetbrains.dr.test:kmp-lib-cinterop-only:1.0 provides variants for the platform iosSimulatorArm64, " +
                        "but none of them can be used",
            ],
            root.errorsOf("kmp-lib-cinterop-only"),
        )
    }

    /**
     * A transitive dependency not supporting some platforms of a shared fragment is not visible there,
     * this holds when the rest of its platforms are blocked by capabilities as well.
     * Only the capabilities problem is reported (it is reported for transitive dependencies too).
     */
    @Test
    fun `unsupported platform of a transitive library is not reported for a shared fragment`() =
        runDrTest {
            val root = rootOfLibraryInMavenLocal("org.jetbrains.dr.test:jvm-lib-depending-on-kmp-capabilities:1.0") { mavenLocal ->
                LibraryWithUnsupportedCapabilities.publish(mavenLocal, publishesLinuxX64 = false)
                LibraryWithUnsupportedCapabilities.publishJvmLibraryDependingOnIt(mavenLocal)
            }
            doTest(root, verifyMessages = false)

            assertEquals(
                [DependencyResolutionDiagnostics.UnsupportedCapabilities.id],
                root.errorIdsOf("kmp-lib-capabilities"),
            )
        }

    /**
     * A JVM library depending on a KMP library that is not published for JVM can't be used on JVM,
     * so the problem is reported even though the KMP library is a transitive dependency.
     */
    @Test
    fun `KMP library without JVM variant reached transitively from a JVM library is reported`() = runDrTest {
        val root = rootOfLibraryInMavenLocal(
            "org.jetbrains.dr.test:jvm-lib-depending-on-kmp:1.0",
            platforms = setOf(ResolutionPlatform.JVM),
        ) { mavenLocal ->
            LibrariesDependingOnLinuxOnlyKmpLibrary.publish(mavenLocal)
        }
        doTest(root, verifyMessages = false)

        assertEquals(
            [
                "Platform jvm is not supported by the library org.jetbrains.dr.test:kmp-lib-linux-only:1.0\n" +
                        "Supported platform: [linuxX64]",
            ],
            root.errorsOf("kmp-lib-linux-only"),
        )
    }

    /**
     * A KMP library depending on a KMP library that is not published for one of its platforms can't be used there,
     * so the problem is reported even though the latter is a transitive dependency.
     */
    @Test
    fun `KMP library without iOS variant reached transitively from a KMP library is reported`() = runDrTest {
        val root = rootOfLibraryInMavenLocal(
            "org.jetbrains.dr.test:kmp-lib-ios:1.0",
            platforms = setOf(ResolutionPlatform.IOS_ARM64),
        ) { mavenLocal ->
            LibrariesDependingOnLinuxOnlyKmpLibrary.publish(mavenLocal)
        }
        doTest(root, verifyMessages = false)

        assertEquals(
            [
                "Platform iosArm64 is not supported by the library org.jetbrains.dr.test:kmp-lib-linux-only:1.0\n" +
                        "Supported platform: [linuxX64]",
            ],
            root.errorsOf("kmp-lib-linux-only"),
        )
    }

    /**
     * A KMP library might declare a JVM-only dependency for a native platform,
     * no variant of such a dependency published with Gradle metadata matches the native platform.
     * It is reported even though it is a transitive dependency, as Gradle fails here as well.
     */
    @Test
    fun `JVM-only library with Gradle metadata reached transitively from a KMP library for native is reported`() =
        runDrTest {
            val root = rootOfLibraryInMavenLocal(
                "org.jetbrains.dr.test:kmp-lib-linux:1.0",
                platforms = setOf(ResolutionPlatform.LINUX_X64),
            ) { mavenLocal ->
                KmpLibraryWithJvmOnlyDependency.publish(mavenLocal)
            }
            doTest(root, verifyMessages = false)

            assertEquals([PlatformsAreNotSupported.ID], root.errorIdsOf("jvm-lib-with-gradle-metadata"))
        }

    /**
     * `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9` publishes its common metadata in the legacy jar format only,
     * which is not supported, so the problem is reported even though it is a transitive dependency.
     */
    @Test
    fun `KMP library with legacy common metadata reached transitively for a shared fragment is reported`() =
        runDrTest {
            val root = rootOfLibraryWithLegacyDependency(
                platforms = setOf(ResolutionPlatform.IOS_ARM64, ResolutionPlatform.IOS_X64),
            )

            assertEquals(
                [DependencyResolutionDiagnostics.KotlinMetadataNotResolved.id],
                root.errorIdsOf("kotlinx-coroutines-core"),
            )
        }

    /**
     * The resolution for a single platform uses the platform artifacts of
     * `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9`, the format of its common metadata doesn't matter there.
     */
    @Test
    fun `KMP library with legacy common metadata reached transitively for a single platform is not a problem`() =
        runDrTest {
            val root = rootOfLibraryWithLegacyDependency(platforms = setOf(ResolutionPlatform.IOS_ARM64))

            assertEquals([], root.errorIdsOf("kotlinx-coroutines-core"))
        }

    private suspend fun rootOfLibraryWithLegacyDependency(platforms: Set<ResolutionPlatform>): DependencyNode {
        val root = rootOfLibraryInMavenLocal(
            "org.jetbrains.dr.test:kmp-lib-legacy-dependency:1.0",
            platforms = platforms,
            repositories = [MAVEN_LOCAL, REDIRECTOR_MAVEN_CENTRAL],
        ) { mavenLocal ->
            KmpLibraryWithLegacyDependency.publish(mavenLocal)
        }
        doTest(root, verifyMessages = false)
        return root
    }

    /**
     * @return the ids of the errors of the node of the given [module]
     */
    private fun DependencyNode.errorIdsOf(module: String): List<String> =
        distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .single { it.module == module }
            .messages
            .filter { it.severity == Severity.ERROR }
            .map { it.id }

    /**
     * @return the detailed messages of the errors of the node of the given [module]
     */
    private fun DependencyNode.errorsOf(module: String): List<String> =
        distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .single { it.module == module }
            .messages
            .filter { it.severity == Severity.ERROR }
            .map { it.detailedMessage }

    private suspend fun resolveLibraryWithoutSources(declaresSourcesVariant: Boolean): DependencyNodeHolderWithContext {
        val root = rootOfLibraryInMavenLocal("org.jetbrains.dr.test:kmp-lib-no-sources:1.0") { mavenLocal ->
            LibraryWithoutSources.publish(mavenLocal, declaresSourcesVariant)
        }
        return doTest(
            root,
            expected = """
                root
                ╰─── org.jetbrains.dr.test:kmp-lib-no-sources:1.0
            """.trimIndent(),
        )
    }

    /**
     * @return the detailed messages of the errors reported for the library
     */
    private suspend fun resolveLibraryWithUnsupportedCapabilities(publishesLinuxX64: Boolean): List<String> {
        val root = rootOfLibraryInMavenLocal("org.jetbrains.dr.test:kmp-lib-capabilities:1.0") { mavenLocal ->
            LibraryWithUnsupportedCapabilities.publish(mavenLocal, publishesLinuxX64)
        }
        doTest(root, verifyMessages = false)

        return root.children.single().messages
            .filter { it.severity == Severity.ERROR }
            .map { it.detailedMessage }
    }

    /**
     * Creates the root of the resolution of the library with the given [coordinates] for the given [platforms],
     * the library is published by [publish] into a fresh mavenLocal repository, and resolved from the [repositories].
     */
    private fun rootOfLibraryInMavenLocal(
        coordinates: String,
        platforms: Set<ResolutionPlatform> = setOf(ResolutionPlatform.IOS_ARM64, ResolutionPlatform.IOS_SIMULATOR_ARM64),
        repositories: List<Repository> = [MAVEN_LOCAL],
        publish: (MavenLocalRepository) -> Unit,
    ): RootDependencyNodeWithContext {
        val testCacheRoot = (tmpDir / UUID.randomUUID().toString().substring(0, 8)).createDirectories()
        val mavenLocal = MavenLocalRepository(testCacheRoot / ".m2.test")
        publish(mavenLocal)

        val context = context(
            platform = platforms,
            repositories = repositories,
            cacheBuilder = mavenLocalCacheBuilder(testCacheRoot, mavenLocal),
        )
        return RootDependencyNodeWithContext(
            children = [coordinates.toMavenNode(context)],
            templateContext = context,
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
     * A KMP library published for iOS without a sources JAR.
     */
    private object LibraryWithoutSources {

        /**
         * Installs the library into a [mavenLocal] repository:
         * an all-metadata JAR, the root module metadata with no sources variant, per-target modules, and no `-sources.jar` anywhere.
         *
         * With [declaresSourcesVariant], the root module metadata additionally declares a sources variant while
         * the sources JAR still isn't there, which mimics a publication broken in a slightly different way.
         */
        fun publish(mavenLocal: MavenLocalRepository, declaresSourcesVariant: Boolean) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-no-sources/1.0").createDirectories()

            (rootDir / "kmp-lib-no-sources-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-no-sources-1.0.module").writeText(rootModuleMetadata(declaresSourcesVariant))
            writeAllMetadataJar(
                rootDir / "kmp-lib-no-sources-1.0.jar",
                PROJECT_STRUCTURE_METADATA,
                sourceSets = ["commonMain", "iosMain"],
            )

            ["iosarm64", "iossimulatorarm64"]
                .forEach { target ->
                    val targetDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-no-sources-$target/1.0")
                        .createDirectories()
                    (targetDir / "kmp-lib-no-sources-$target-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
                    (targetDir / "kmp-lib-no-sources-$target-1.0.module").writeText(leafModuleMetadata(target))
                    (targetDir / "kmp-lib-no-sources-$target-1.0.klib").writeText("fake klib of $target")
                }
        }

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

    /**
     * A KMP library whose `iosArm64` variant declares unsupported capabilities,
     * and which is not published for `iosSimulatorArm64` at all.
     */
    private object LibraryWithUnsupportedCapabilities {

        /**
         * Installs the root publication of the library into a [mavenLocal] repository.
         * Platform publications are not needed: the variants of the root module metadata are rejected before reaching them.
         *
         * With [publishesLinuxX64], the library is additionally published for `linuxX64`, which is not requested.
         */
        fun publish(mavenLocal: MavenLocalRepository, publishesLinuxX64: Boolean) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-capabilities/1.0").createDirectories()

            (rootDir / "kmp-lib-capabilities-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-capabilities-1.0.module").writeText(rootModuleMetadata(publishesLinuxX64))
            writeAllMetadataJar(
                rootDir / "kmp-lib-capabilities-1.0.jar",
                PROJECT_STRUCTURE_METADATA,
                sourceSets = ["commonMain"],
            )
        }

        /** Installs a JVM library published with `pom.xml` only, depending on the library. */
        fun publishJvmLibraryDependingOnIt(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/jvm-lib-depending-on-kmp-capabilities/1.0")
                .createDirectories()
            (rootDir / "jvm-lib-depending-on-kmp-capabilities-1.0.pom").writeText(
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>org.jetbrains.dr.test</groupId>
                      <artifactId>jvm-lib-depending-on-kmp-capabilities</artifactId>
                      <version>1.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.jetbrains.dr.test</groupId>
                          <artifactId>kmp-lib-capabilities</artifactId>
                          <version>1.0</version>
                          <scope>compile</scope>
                        </dependency>
                      </dependencies>
                    </project>
                """.trimIndent()
            )
            (rootDir / "jvm-lib-depending-on-kmp-capabilities-1.0.jar").writeText("fake jar")
        }

        fun platformVariant(
            name: String,
            nativeTarget: String,
            module: String,
            extraCapabilities: String = "",
            usage: String = "kotlin-api",
        ) = """
            {
              "name": "$name",
              "attributes": {
                "artifactType": "org.jetbrains.kotlin.klib",
                "org.gradle.category": "library",
                "org.gradle.jvm.environment": "non-jvm",
                "org.gradle.usage": "$usage",
                "org.jetbrains.kotlin.native.target": "$nativeTarget",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../$module/1.0/$module-1.0.module",
                "group": "org.jetbrains.dr.test",
                "module": "$module",
                "version": "1.0"
              }$extraCapabilities
            }
        """.trimIndent()

        /** The legacy capability declared in addition to the own one makes the variant rejected. */
        val IOS_ARM64_VARIANT = platformVariant(
            name = "iosArm64ApiElements-published",
            nativeTarget = "ios_arm64",
            module = "kmp-lib-capabilities-iosarm64",
            extraCapabilities = """,
              "capabilities": [
                { "group": "org.jetbrains.dr.test", "name": "kmp-lib-capabilities", "version": "1.0" },
                { "group": "org.jetbrains.dr.test.legacy", "name": "kmp-lib-capabilities", "version": "1.0" }
              ]""",
        )

        val LINUX_X64_VARIANT = platformVariant(
            name = "linuxX64ApiElements-published",
            nativeTarget = "linux_x64",
            module = "kmp-lib-capabilities-linuxx64",
        )

        fun rootModuleMetadata(publishesLinuxX64: Boolean) = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "org.jetbrains.dr.test",
                "module": "kmp-lib-capabilities",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
              "variants": [
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
                      "name": "kmp-lib-capabilities-metadata-1.0.jar",
                      "url": "kmp-lib-capabilities-1.0.jar",
                      "size": -1
                    }
                  ]
                },
                ${if (publishesLinuxX64) "$LINUX_X64_VARIANT," else ""}
                $IOS_ARM64_VARIANT
              ]
            }
        """.trimIndent()

        val PROJECT_STRUCTURE_METADATA = """
            {
              "projectStructure": {
                "formatVersion": "0.3.3",
                "isPublishedAsRoot": "true",
                "variants": [
                  {
                    "name": "iosArm64ApiElements",
                    "sourceSet": [ "commonMain" ]
                  },
                  {
                    "name": "linuxX64ApiElements",
                    "sourceSet": [ "commonMain" ]
                  }
                ],
                "sourceSets": [
                  {
                    "name": "commonMain",
                    "dependsOn": [],
                    "moduleDependency": [],
                    "binaryLayout": "klib"
                  }
                ]
              }
            }
        """.trimIndent()
    }

    /**
     * A KMP library whose only `iosSimulatorArm64` variant has the `kotlin-cinterop` usage,
     * which matches no resolution scope.
     * Platform publications are not needed: the variant of the root module metadata is rejected before reaching them.
     */
    private object LibraryWithCinteropOnlyVariant {

        fun publish(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-cinterop-only/1.0").createDirectories()
            (rootDir / "kmp-lib-cinterop-only-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-cinterop-only-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-cinterop-only",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
                        ${LibraryWithUnsupportedCapabilities.platformVariant(
                            name = "iosSimulatorArm64CInteropApiElements-published",
                            nativeTarget = "ios_simulator_arm64",
                            module = "kmp-lib-cinterop-only-iossimulatorarm64",
                            usage = "kotlin-cinterop",
                        )}
                      ]
                    }
                """.trimIndent()
            )
        }
    }

    /**
     * A KMP library published for `linuxX64` only, and two libraries depending on it:
     * a JVM library published with `pom.xml` only, and a KMP library published for `iosArm64`.
     */
    private object LibrariesDependingOnLinuxOnlyKmpLibrary {

        fun publish(mavenLocal: MavenLocalRepository) {
            publishLinuxOnlyKmpLibrary(mavenLocal)
            publishJvmLibrary(mavenLocal)
            publishIosKmpLibrary(mavenLocal)
        }

        /**
         * Platform publications are not needed: no variant of the root module metadata matches the requested platforms.
         */
        private fun publishLinuxOnlyKmpLibrary(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-linux-only/1.0").createDirectories()
            (rootDir / "kmp-lib-linux-only-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-linux-only-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-linux-only",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
                        ${LibraryWithUnsupportedCapabilities.platformVariant(
                            name = "linuxX64ApiElements-published",
                            nativeTarget = "linux_x64",
                            module = "kmp-lib-linux-only-linuxx64",
                        )}
                      ]
                    }
                """.trimIndent()
            )
        }

        private fun publishJvmLibrary(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/jvm-lib-depending-on-kmp/1.0").createDirectories()
            (rootDir / "jvm-lib-depending-on-kmp-1.0.pom").writeText(
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>org.jetbrains.dr.test</groupId>
                      <artifactId>jvm-lib-depending-on-kmp</artifactId>
                      <version>1.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.jetbrains.dr.test</groupId>
                          <artifactId>kmp-lib-linux-only</artifactId>
                          <version>1.0</version>
                          <scope>compile</scope>
                        </dependency>
                      </dependencies>
                    </project>
                """.trimIndent()
            )
            (rootDir / "jvm-lib-depending-on-kmp-1.0.jar").writeText("fake jar")
        }

        private fun publishIosKmpLibrary(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-ios/1.0").createDirectories()
            (rootDir / "kmp-lib-ios-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-ios-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-ios",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
                        ${LibraryWithUnsupportedCapabilities.platformVariant(
                            name = "iosArm64ApiElements-published",
                            nativeTarget = "ios_arm64",
                            module = "kmp-lib-ios-iosarm64",
                        )}
                      ]
                    }
                """.trimIndent()
            )

            val iosDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-ios-iosarm64/1.0").createDirectories()
            (iosDir / "kmp-lib-ios-iosarm64-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (iosDir / "kmp-lib-ios-iosarm64-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-ios-iosarm64",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
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
                          "dependencies": [
                            {
                              "group": "org.jetbrains.dr.test",
                              "module": "kmp-lib-linux-only",
                              "version": { "requires": "1.0" }
                            }
                          ],
                          "files": [
                            {
                              "name": "kmp-lib-ios-iosarm64-1.0.klib",
                              "url": "kmp-lib-ios-iosarm64-1.0.klib",
                              "size": -1
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
            (iosDir / "kmp-lib-ios-iosarm64-1.0.klib").writeText("fake klib of iosarm64")
        }
    }

    /**
     * A KMP library published for `linuxX64` whose `linuxX64` variant depends on a JVM-only library
     * published with Gradle metadata, all variants of which declare the `jvm` platform type
     * (like `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.8.0`).
     */
    private object KmpLibraryWithJvmOnlyDependency {

        fun publish(mavenLocal: MavenLocalRepository) {
            publishJvmLibrary(mavenLocal)
            publishKmpLibrary(mavenLocal)
        }

        private fun publishJvmLibrary(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/jvm-lib-with-gradle-metadata/1.0").createDirectories()
            (rootDir / "jvm-lib-with-gradle-metadata-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "jvm-lib-with-gradle-metadata-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "jvm-lib-with-gradle-metadata",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "gradle": { "version": "9.8.0" } },
                      "variants": [
                        ${jvmVariant(name = "apiElements", usage = "java-api")},
                        ${jvmVariant(name = "runtimeElements", usage = "java-runtime")}
                      ]
                    }
                """.trimIndent()
            )
            (rootDir / "jvm-lib-with-gradle-metadata-1.0.jar").writeText("fake jar")
        }

        private fun jvmVariant(name: String, usage: String) = """
            {
              "name": "$name",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.jvm.environment": "standard-jvm",
                "org.gradle.libraryelements": "jar",
                "org.gradle.usage": "$usage",
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "files": [
                {
                  "name": "jvm-lib-with-gradle-metadata-1.0.jar",
                  "url": "jvm-lib-with-gradle-metadata-1.0.jar",
                  "size": -1
                }
              ]
            }
        """.trimIndent()

        private fun publishKmpLibrary(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-linux/1.0").createDirectories()
            (rootDir / "kmp-lib-linux-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-linux-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-linux",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
                        ${LibraryWithUnsupportedCapabilities.platformVariant(
                            name = "linuxX64ApiElements-published",
                            nativeTarget = "linux_x64",
                            module = "kmp-lib-linux-linuxx64",
                        )}
                      ]
                    }
                """.trimIndent()
            )

            val linuxDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-linux-linuxx64/1.0").createDirectories()
            (linuxDir / "kmp-lib-linux-linuxx64-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (linuxDir / "kmp-lib-linux-linuxx64-1.0.module").writeText(
                """
                    {
                      "formatVersion": "1.1",
                      "component": {
                        "group": "org.jetbrains.dr.test",
                        "module": "kmp-lib-linux-linuxx64",
                        "version": "1.0",
                        "attributes": { "org.gradle.status": "release" }
                      },
                      "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
                      "variants": [
                        {
                          "name": "linuxX64ApiElements-published",
                          "attributes": {
                            "artifactType": "org.jetbrains.kotlin.klib",
                            "org.gradle.category": "library",
                            "org.gradle.jvm.environment": "non-jvm",
                            "org.gradle.usage": "kotlin-api",
                            "org.jetbrains.kotlin.native.target": "linux_x64",
                            "org.jetbrains.kotlin.platform.type": "native"
                          },
                          "dependencies": [
                            {
                              "group": "org.jetbrains.dr.test",
                              "module": "jvm-lib-with-gradle-metadata",
                              "version": { "requires": "1.0" }
                            }
                          ],
                          "files": [
                            {
                              "name": "kmp-lib-linux-linuxx64-1.0.klib",
                              "url": "kmp-lib-linux-linuxx64-1.0.klib",
                              "size": -1
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
            (linuxDir / "kmp-lib-linux-linuxx64-1.0.klib").writeText("fake klib of linuxx64")
        }
    }

    /**
     * A KMP library published for `iosArm64` and `iosX64` (both supported by `kotlinx-coroutines-core:1.3.9`),
     * its 'commonMain' depends on `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9`.
     */
    private object KmpLibraryWithLegacyDependency {

        private val targets = mapOf("iosarm64" to "ios_arm64", "iosx64" to "ios_x64")

        fun publish(mavenLocal: MavenLocalRepository) {
            val rootDir = (mavenLocal.repository / "org/jetbrains/dr/test/kmp-lib-legacy-dependency/1.0").createDirectories()
            (rootDir / "kmp-lib-legacy-dependency-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
            (rootDir / "kmp-lib-legacy-dependency-1.0.module").writeText(rootModuleMetadata())
            writeAllMetadataJar(
                rootDir / "kmp-lib-legacy-dependency-1.0.jar",
                PROJECT_STRUCTURE_METADATA,
                sourceSets = ["commonMain", "iosMain"],
            )

            targets.forEach { target ->
                val module = "kmp-lib-legacy-dependency-${target.key}"
                val targetDir = (mavenLocal.repository / "org/jetbrains/dr/test/$module/1.0").createDirectories()
                (targetDir / "$module-1.0.pom").writeText(GRADLE_METADATA_MARKER_POM)
                (targetDir / "$module-1.0.module").writeText(platformModuleMetadata(module, target.value))
                (targetDir / "$module-1.0.klib").writeText("fake klib of ${target.key}")
            }
        }

        private const val COROUTINES_DEPENDENCY = """
            {
              "group": "org.jetbrains.kotlinx",
              "module": "kotlinx-coroutines-core",
              "version": { "requires": "1.3.9" }
            }
        """

        private fun rootModuleMetadata() = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "org.jetbrains.dr.test",
                "module": "kmp-lib-legacy-dependency",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
              "variants": [
                {
                  "name": "metadataApiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-metadata",
                    "org.jetbrains.kotlin.platform.type": "common"
                  },
                  "dependencies": [ $COROUTINES_DEPENDENCY ],
                  "files": [
                    {
                      "name": "kmp-lib-legacy-dependency-metadata-1.0.jar",
                      "url": "kmp-lib-legacy-dependency-1.0.jar",
                      "size": -1
                    }
                  ]
                },
                ${targets.map { target ->
                    LibraryWithUnsupportedCapabilities.platformVariant(
                        name = "${target.value.toVariantPrefix()}ApiElements-published",
                        nativeTarget = target.value,
                        module = "kmp-lib-legacy-dependency-${target.key}",
                    )
                }.joinToString(",\n")}
              ]
            }
        """.trimIndent()

        private fun platformModuleMetadata(module: String, nativeTarget: String) = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "org.jetbrains.dr.test",
                "module": "$module",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "kotlinToolchain": { "version": "1.0-SNAPSHOT" } },
              "variants": [
                {
                  "name": "${nativeTarget.toVariantPrefix()}ApiElements-published",
                  "attributes": {
                    "artifactType": "org.jetbrains.kotlin.klib",
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "non-jvm",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "$nativeTarget",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "dependencies": [ $COROUTINES_DEPENDENCY ],
                  "files": [
                    {
                      "name": "$module-1.0.klib",
                      "url": "$module-1.0.klib",
                      "size": -1
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        /** `ios_arm64` -> `iosArm64` */
        private fun String.toVariantPrefix() =
            split("_").mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

        private val PROJECT_STRUCTURE_METADATA = """
            {
              "projectStructure": {
                "formatVersion": "0.3.3",
                "isPublishedAsRoot": "true",
                "variants": [
                  { "name": "iosArm64ApiElements", "sourceSet": [ "iosMain", "commonMain" ] },
                  { "name": "iosX64ApiElements", "sourceSet": [ "iosMain", "commonMain" ] }
                ],
                "sourceSets": [
                  {
                    "name": "commonMain",
                    "dependsOn": [],
                    "moduleDependency": [ "org.jetbrains.kotlinx:kotlinx-coroutines-core" ],
                    "binaryLayout": "klib"
                  },
                  {
                    "name": "iosMain",
                    "dependsOn": [ "commonMain" ],
                    "moduleDependency": [ "org.jetbrains.kotlinx:kotlinx-coroutines-core" ],
                    "binaryLayout": "klib"
                  }
                ]
              }
            }
        """.trimIndent()
    }

    private companion object {

        /** The marker that makes dependency resolution prefer Gradle module metadata over this POM. */
        const val GRADLE_METADATA_MARKER_POM = "do_not_remove: published-with-gradle-metadata"

        /**
         * Writes the all-metadata JAR: the [projectStructureMetadata] descriptor plus one directory per published source set.
         */
        fun writeAllMetadataJar(target: Path, projectStructureMetadata: String, sourceSets: List<String>) {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.writeEntry("META-INF/kotlin-project-structure-metadata.json", projectStructureMetadata)
                sourceSets.forEach { sourceSet ->
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
    }
}
