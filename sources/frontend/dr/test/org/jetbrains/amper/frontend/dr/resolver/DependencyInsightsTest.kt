/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.MavenDependencyConstraintNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.originalVersion
import org.jetbrains.amper.dependency.resolution.resolvedVersion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("gold-file")
open class DependencyInsightsTest : AbstractDependencyInsightsTest() {
    override val testGoldenFilesRoot: Path = super.testGoldenFilesRoot / "dependencyInsights"

    @Test
    fun `test sync empty jvm module`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-empty", testDataRoot)

        assertEquals(
            setOf("main", "test"),
            aom.modules[0].fragments.map { it.name }.toSet(),
            "",
        )

        val jvmEmptyModuleGraph = doTestByFile(
            testInfo,
            aom,
            resolutionInput = ideSyncTestResolutionInput,
            module = "jvm-empty",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlin",
            module = "kotlin-stdlib",
            graph = jvmEmptyModuleGraph,
            testInfo = testInfo,
        )
        assertInsightByFile(
            group = "org.opentest4j",
            module = "opentest4j",
            graph = jvmEmptyModuleGraph,
            testInfo = testInfo,
        )
        assertInsightByFile(
            group = "org.jetbrains.kotlin",
            module = "kotlin-test-junit5",
            graph = jvmEmptyModuleGraph,
            testInfo = testInfo,
        )
        assertInsight(
            group = "org.jetbrains.kotlin", module = "XXX", graph = jvmEmptyModuleGraph,
            expected = ""
        )
        assertInsight(
            group = "XXX", module = "kotlin-test-junit", graph = jvmEmptyModuleGraph,
            expected = ""
        )
    }

    @Test
    fun `test compose-multiplatform - shared compile dependencies insights`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val sharedModuleIosArm64Graph = doTestByFile(
            testInfo,
            aom,
            module = "shared",
            filter = ModuleResolutionFilter(
                scope = ResolutionScope.COMPILE,
                platforms = setOf(ResolutionPlatform.IOS_ARM64),
            ),
        )

        // Subgraph for "org.jetbrains.kotlin:kotlin-stdlib" shows places referencing the dependency
        // of the exact effective version only (${DefaultVersions.kotlin}).
        // There are other paths to this dependency referencing another version of this dependency, those are skipped as well as same-version constraints.
        assertInsightByFile(
            group = "org.jetbrains.compose.ui",
            module = "ui-graphics",
            graph = sharedModuleIosArm64Graph,
            testInfo = testInfo,
        )

        // Subgraph for "org.jetbrains.kotlin:kotlin-stdlib-common" shows all places referencing the dependency
        // since none of those places references the exact effective version (${DefaultVersions.kotlin}).
        // Also, the path to the constraint defining the effective version (${DefaultVersions.kotlin}) is also presented in a graph.
        assertInsightByFile(
            group = "org.jetbrains.kotlin",
            module = "kotlin-stdlib-common",
            graph = sharedModuleIosArm64Graph,
            testInfo = testInfo,
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-coroutines-core",
            graph = sharedModuleIosArm64Graph,
            testInfo = testInfo,
        )

        // Assert that all dependencies "org.jetbrains.kotlin:kotlin-stdlib-common" have correct overriddenBy
        sharedModuleIosArm64Graph
            .distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .filter { it.group == "org.jetbrains.kotlin" && it.module == "kotlin-stdlib-common" }
            .forEach {
                if (it.originalVersion() == it.resolvedVersion()) {
                    assertNull(it.overriddenBy)
                } else {
                    assertNotNull(
                        it.overriddenBy,
                        "Expected non-null 'overriddenBy' since ${it.resolvedVersion()} doesn't match ${it.originalVersion()}"
                    )
                    val constraintNodes = it.overriddenBy.filterIsInstance<MavenDependencyConstraintNode>().takeIf { it.isNotEmpty() }
                    assertNotNull(
                        constraintNodes,
                        "Expected at least one dependency constraint node in 'overriddenBy', but found ${
                            it.overriddenBy.map { it.key }.toSet()
                        }"
                    )
                    val key = constraintNodes.first().key
                    assertEquals(
                        key.name, "org.jetbrains.kotlin:kotlin-stdlib-common",
                        "Unexpected constraint ${key}"
                    )
                }
            }
    }

    /**
     * This test checks that a large number of overridden dependencies don't
     * cause performance degradation.
     * // todo (AB) : [AMPER-4905] This test is added temporary and should be removed (it doesn't check anything that other tests checks
     * // todo (AB) : [AMPER-4905] It is added for debug purposes.
     */
    @Test
    fun `test large number of overridden dependencies`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("multiplatfrorm-large-with-overridden-versions", testDataRoot)

        val iosAppMainGraph = timed("doTestByFile") {
            doTestByFile(
                testInfo,
                aom,
                module = "ios-app",
                filter = ModuleResolutionFilter(
                    scope = ResolutionScope.COMPILE,
                    platforms = setOf(ResolutionPlatform.IOS_ARM64),
                ),
                verifyMessages = false,
            )
        }

        assertInsightByFile(
            group = "org.jetbrains.compose.runtime",
            module = "runtime",
            graph = iosAppMainGraph,
            testInfo = testInfo,
        )
    }

    /**
     * Test checks that if a node has a child that caused version overriding,
     * then other children of this node are not included in the insight graph
     * built for a resolved version diagnostic.
     */
    @Test
    fun `test jvm-dependency-insights - A`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val aGraph = doTestByFile(
            testInfo,
            aom,
            module = "A",
            filter = ModuleResolutionFilter(
                scope = ResolutionScope.COMPILE,
                platforms = setOf(ResolutionPlatform.JVM),
            ),
        )

        // Subgraph for "org.jetbrains.kotlinx:kotlin-coroutine-core" shows places referencing the dependency
        // of the exact effective version only (${DefaultVersions.kotlin}).
        // There are other paths to this dependency referencing another version of this dependency, those are skipped as well as same-version constraints.
        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-coroutines-core",
            graph = aGraph,
            testInfo = testInfo,
        )

        // Assert that all dependencies "org.jetbrains.kotlin:kotlinx-coroutines-core" have a correct overriddenBy
        aGraph
            .distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .filter { it.group == "org.jetbrains.kotlinx" && it.module == "kotlinx-coroutines-core" }
            .forEach {
                if (it.originalVersion() == it.resolvedVersion()) {
                    assertTrue(
                        actual = it.overriddenBy.isEmpty(),
                        message = "Expected overriddenBy to be empty for ${it.key} since its resolved version " +
                                "matches the original version ${it.originalVersion()}",
                    )
                } else {
                    assertNotNull(
                        it.overriddenBy,
                        "Expected non-null 'overriddenBy' since ${it.resolvedVersion()} doesn't match ${it.originalVersion()}"
                    )
                    val dependencyNodes = it.overriddenBy.filterIsInstance<MavenDependencyNode>()
                    assertEquals(
                        dependencyNodes.size,
                        2,
                        "Expected exactly 2 MavenDependencyNode in 'overriddenBy', but found ${
                            it.overriddenBy.map { it.key }.toSet()
                        }"
                    )
                    val key = dependencyNodes.first().key
                    assertEquals(
                        key.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                        "Unexpected dependency node $key"
                    )
                    val constraintNode = it.overriddenBy.filterIsInstance<MavenDependencyConstraintNode>().singleOrNull()
                    if (constraintNode != null) {
                        assertEquals(
                            constraintNode.key.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                            "Unexpected constraint node ${constraintNode.key}"
                        )
                    }
                }
            }
    }

    @Test
    fun `test jvm-dependency-insights - B`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val bGraph = doTestByFile(
            testInfo,
            aom,
            module = "B",
            filter = ModuleResolutionFilter(
                scope = ResolutionScope.COMPILE,
                platforms = setOf(ResolutionPlatform.JVM),
            ),
        )

        // Subgraph for "org.jetbrains.kotlinx:kotlin-coroutine-core" shows places referencing the dependency
        // of the exact effective version only (${DefaultVersions.kotlin}).
        // There are other paths to this dependency referencing another version of this dependency, those are skipped as well as same-version constraints.
        assertInsightByFile(
            group = "org.jetbrains.compose.runtime",
            module = "runtime",
            graph = bGraph,
            testInfo = testInfo,
        )
    }

    @Test
    fun `test jvm-dependency-insights - C`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val cGraph = doTestByFile(
            testInfo,
            aom,
            module = "C",
            filter = ModuleResolutionFilter(
                scope = ResolutionScope.COMPILE,
                platforms = setOf(ResolutionPlatform.JVM),
            ),
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-coroutines-test",
            graph = cGraph,
            testInfo = testInfo,
        )
    }

    @Test
    fun `test jvm-dependency-insights - D`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val dGraph = doTestByFile(
            testInfo,
            aom,
            module = "D",
            filter = ModuleResolutionFilter(
                scope = ResolutionScope.COMPILE,
                platforms = setOf(ResolutionPlatform.JVM),
            ),
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-coroutines-test",
            graph = dGraph,
            testInfo = testInfo,
        )
    }

    /**
     * This test checks that insights about overridden dependency are correctly reported in case
     * an overridden version comes from the BOM constraint.
     */
    @Test
    fun `test jvm-dependency-insights - E`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val eGraph = doTestByFile(
            testInfo,
            aom,
            module = "E",
        )

        assertInsightByFile(
            group = "org.junit.jupiter",
            module = "junit-jupiter-api",
            graph = eGraph,
            testInfo = testInfo,
        )
    }

    /**
     * This test checks that insights about overridden dependency are correctly reported in case
     * an overridden version comes from the local module dependency (module C) via RUNTIME scope
     * and is not presented among dependencies of module G (that depends on C).
     */
    @Test
    fun `test jvm-dependency-insights - G`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val gGraph = doTestByFile(
            testInfo,
            aom,
            module = "G",
        )

        // Important: graph filtered by COMPILE classpath is not enough to calculate
        // resolved version insight, because the resolved version of 'kotlinx-coroutines-core'
        // comes from RUNTIME graph (version is overridden in module C, and G depends on C)
        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-coroutines-core",
            graph = gGraph,
            testInfo = testInfo,
        )
    }

    /**
     * This test checks that in full mode, all pathes to the required dependency are shown.
     */
    @Test
    fun `test jvm-dependency-insights - F`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-dependency-insights", testDataRoot)

        val eGraph = doTestByFile(
            testInfo,
            aom,
            module = "F",
            filter = ModuleResolutionFilter(scope = ResolutionScope.COMPILE),
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlin",
            module = "kotlin-stdlib",
            graph = eGraph,
            testInfo = testInfo,
        )
    }
}
