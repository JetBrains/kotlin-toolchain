/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.dr.toMavenNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverTest: BaseDRTest() {

    @TempDir
    lateinit var tmpDir: Path

    fun uniqueNestedTempDir(): Path = tmpDir.resolve(UUID.randomUUID().toString().substring(0, 10))

    override val testDataPath: Path
        get() = super.testDataPath.resolve("resolver")

    @Test
    fun `junit-jupiter-params resolved in two contexts (COMPILE, RUNTIME)`() = runDrTest {
        val jupiterParamsCoordinates = "org.junit.jupiter:junit-jupiter-params:5.7.2"

        val nodeInCompileContext = jupiterParamsCoordinates.toMavenNode(context(ResolutionScope.COMPILE))
        val nodeInRuntimeContext = jupiterParamsCoordinates.toMavenNode(context(ResolutionScope.RUNTIME))

        val root = RootDependencyNodeWithContext(
            children = listOf(nodeInCompileContext, nodeInRuntimeContext),
            templateContext = context()
        )

        doTest(
            root,
            expected = """
            root
            ├─── org.junit.jupiter:junit-jupiter-params:5.7.2
            │    ├─── org.junit:junit-bom:5.7.2
            │    ├─── org.apiguardian:apiguardian-api:1.1.0
            │    ╰─── org.junit.jupiter:junit-jupiter-api:5.7.2
            │         ├─── org.junit:junit-bom:5.7.2
            │         ├─── org.apiguardian:apiguardian-api:1.1.0
            │         ├─── org.opentest4j:opentest4j:1.2.0
            │         ╰─── org.junit.platform:junit-platform-commons:1.7.2
            │              ├─── org.junit:junit-bom:5.7.2
            │              ╰─── org.apiguardian:apiguardian-api:1.1.0
            ╰─── org.junit.jupiter:junit-jupiter-params:5.7.2
                 ├─── org.junit:junit-bom:5.7.2
                 ├─── org.apiguardian:apiguardian-api:1.1.0
                 ╰─── org.junit.jupiter:junit-jupiter-api:5.7.2
                      ├─── org.junit:junit-bom:5.7.2
                      ├─── org.apiguardian:apiguardian-api:1.1.0
                      ├─── org.opentest4j:opentest4j:1.2.0
                      ╰─── org.junit.platform:junit-platform-commons:1.7.2
                           ├─── org.junit:junit-bom:5.7.2
                           ╰─── org.apiguardian:apiguardian-api:1.1.0
            """.trimIndent(),
            verifyMessages = false
        )

        downloadAndAssertFiles(
            listOf(
                "apiguardian-api-1.1.0.jar",
                "junit-jupiter-api-5.7.2.jar",
                "junit-jupiter-params-5.7.2.jar",
                "junit-platform-commons-1.7.2.jar",
                "opentest4j-1.2.0.jar",
            ),
            root
        )
    }

    @Test
    fun `kmp library sources downloaded`(testInfo: TestInfo) = runDrTest {
        val kmpLibrary = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0"

        val root = doTest(
            testInfo,
            dependency = listOf(kmpLibrary),
            platform = setOf(ResolutionPlatform.IOS_ARM64, ResolutionPlatform.IOS_SIMULATOR_ARM64),
            expected = """
                root
                ╰─── org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0
                     ├─── org.jetbrains.kotlinx:atomicfu:0.23.1
                     │    ├─── org.jetbrains.kotlin:kotlin-stdlib:1.9.21
                     │    ╰─── org.jetbrains.kotlin:kotlin-stdlib-common:1.9.21
                     │         ╰─── org.jetbrains.kotlin:kotlin-stdlib:1.9.21
                     ╰─── org.jetbrains.kotlin:kotlin-stdlib:1.9.21
            """.trimIndent(),
            verifyMessages = false,
        )

        downloadAndAssertFiles(
            listOf(
                "atomicfu-commonMain-0.23.1-sources.jar",
                "atomicfu-commonMain-0.23.1.klib",
                "atomicfu-nativeMain-0.23.1-sources.jar",
                "atomicfu-nativeMain-0.23.1.klib",
                "kotlin-stdlib-commonMain-1.9.21-sources.jar",
                "kotlin-stdlib-commonMain-1.9.21.klib",
                "kotlinx-coroutines-core-commonMain-1.8.0-sources.jar",
                "kotlinx-coroutines-core-commonMain-1.8.0.klib",
                "kotlinx-coroutines-core-concurrentMain-1.8.0-sources.jar",
                "kotlinx-coroutines-core-concurrentMain-1.8.0.klib",
                "kotlinx-coroutines-core-nativeDarwinMain-1.8.0-sources.jar",
                "kotlinx-coroutines-core-nativeDarwinMain-1.8.0.klib",
                "kotlinx-coroutines-core-nativeMain-1.8.0-sources.jar",
                "kotlinx-coroutines-core-nativeMain-1.8.0.klib",
                "org.jetbrains.kotlinx_atomicfu-cinterop-interop.klib"
            ),
            withSources = true,
            root = root
        )
    }

    @Test
    fun `sources downloaded even if variant is not defined in Gradle metadata`(testInfo: TestInfo) = runDrTest {
        val library = "com.fasterxml.jackson.core:jackson-core:2.17.2"

        val root = doTest(
            testInfo,
            dependency = listOf(library),
            platform = setOf(ResolutionPlatform.JVM),
            expected = """
                root
                ╰─── com.fasterxml.jackson.core:jackson-core:2.17.2
                     ╰─── com.fasterxml.jackson:jackson-bom:2.17.2
            """.trimIndent(),
            verifyMessages = false,
        )

        downloadAndAssertFiles(
            listOf(
                "jackson-core-2.17.2-sources.jar",
                "jackson-core-2.17.2.jar",
            ),
            withSources = true,
            root = root
        )
    }

    @Test
    fun `invalid version is correctly reported`(testInfo: TestInfo) = runDrTest {
        val library = "com.fasterxml.jackson.core:jackson-core:2.17.2 - ../shared"

        val root = doTest(
            testInfo,
            dependency = listOf(library),
            platform = setOf(ResolutionPlatform.JVM),
            expected = """
                root
                ╰─── com.fasterxml.jackson.core:jackson-core:2.17.2 - ../shared
            """.trimIndent(),
            verifyMessages = false,
        )

        val node = root.distinctBfsSequence().filterIsInstance<MavenDependencyNode>().single()
        assertEquals(
            1, node.messages.size,
            "Expected exactly one error message instead of ${node.messages.size}: ${node.messages}"
        )
        
        val expectedErrorPath = Dirs.userCacheRoot.resolve(".m2.cache/com/fasterxml/jackson/core/jackson-core/2.17.2 - ../shared")

        assertEquals(
            "Unable to resolve dependency com.fasterxml.jackson.core:jackson-core:2.17.2 - ../shared",
            node.messages.single().message,
            "Unexpected error message"
        )

        assertTrue(
            // Windows
            node.messages.single().detailedMessage.contains("NoSuchFileException: $expectedErrorPath")
                    || node.messages.single().detailedMessage.contains("AccessDeniedException:")
                    // Linux, MacOS
                    || node.messages.single().detailedMessage.contains("java.lang.IllegalArgumentException: Illegal character in path at index"),
            "Unexpected detailed error message: \n ${node.messages.single().detailedMessage}"
        )
    }

    /**
     * This test checks that sources are downloaded actually by [Resolver.downloadDependencies]
     * if a dependency graph was downloaded before WITHOUT sources already.
     */
    @Test
    fun `com_github_ajalt_clikt clikt-core-wasm-js 5_0_3`(testInfo: TestInfo) = runDrTest {

        val defaultCacheRoot = Dirs.userCacheRoot

        val platforms = setOf(ResolutionPlatform.WASM_JS)
        val dependency = [testInfo.nameToDependency()]

        val resolver = Resolver()

        // 1. Populating default test local storage with all related dependencies, including source files.
        dependency.also {
            val defaultContext = context(
                platform = platforms,
                cacheBuilder = cacheBuilder(defaultCacheRoot),
            )
            val root = dependency.toRootNode(defaultContext)
            resolver.resolveDependencies(root, ResolutionLevel.NETWORK,
                downloadSources = true, transitive = false, incrementalCacheUsage = IncrementalCacheUsage.SKIP)
            downloadAndAssertFiles(testInfo, root, withSources = true)
        }

        // Checking that the sources are downloaded on the second attempt after graph was initially downloaded without them
        dependency.also {
            val cleanCacheRoot = uniqueNestedTempDir()

            // 2. Populating clean test local storage with all related dependencies, excluding a source file.
            val cliktRelativePath = Path(".m2.cache/com/github/ajalt/clikt/clikt-core-wasm-js/5.0.3")
            val cliktDirInCleanCacheRoot = cleanCacheRoot.resolve(cliktRelativePath).createDirectories()

            // Populating clean cache with artifacts (except sources)
            defaultCacheRoot.resolve(cliktRelativePath)
                .copyToRecursively(cliktDirInCleanCacheRoot, followLinks = false, overwrite = false)
            cliktDirInCleanCacheRoot.resolve("clikt-core-wasm-js-5.0.3-sources.jar").deleteIfExists()

            // 3. Preparing resolution context that will reuse artifacts from the default cache
            val cleanContext = context(
                platform = platforms,
                cacheBuilder = {
                    // Using clean local artifact storage for the test
                    cacheBuilder(cleanCacheRoot).invoke(this)
                    // This allows reusing the shared cache on TC and locally (avoiding redownloading already cached dependencies).
                    // At the same time, the test could check what is presented/missing in its own test-specific local storage.
                    this.readOnlyExternalRepositories = [MavenLocalRepository(defaultCacheRoot.resolve(".m2.cache"))]
                },
            )

            // 4. Resolving dependencies WITHOUT sources. As a result, test artifacts storage won't contain sources.
            val root = dependency.toRootNode(cleanContext)
            resolver.resolveDependencies(
                root, ResolutionLevel.NETWORK, downloadSources = false, transitive = false, incrementalCacheUsage = IncrementalCacheUsage.SKIP)

            val sourcesFile = root.distinctBfsSequence()
                .filterIsInstance<MavenDependencyNode>()
                .flatMap { it.dependency.files(true) }
                .singleOrNull { it.path?.name == "clikt-core-wasm-js-5.0.3-sources.jar" }

            assertNull(sourcesFile) { "Existing sources file is found in the resolved dependencies graph" }

            // 5. Resolving dependencies WITH sources. As a result, test artifacts storage will contain sources.
            downloadAndAssertFiles(testInfo, root, withSources = true)

            val sourcesFileAfterRedownloading = root.distinctBfsSequence()
                .filterIsInstance<MavenDependencyNode>()
                .flatMap { it.dependency.files(true) }
                .singleOrNull { it.path?.name == "clikt-core-wasm-js-5.0.3-sources.jar" }

            assertNotNull(sourcesFileAfterRedownloading) { "Sources file is not found in the resolved dependencies graph" }
            assertTrue(sourcesFileAfterRedownloading.path!!.exists(), "Sources file should has been downloaded")
        }
    }
}
