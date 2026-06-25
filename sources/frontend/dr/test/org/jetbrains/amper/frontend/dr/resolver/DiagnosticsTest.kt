/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.orUnspecified
import org.jetbrains.amper.dependency.resolution.version
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.collectBuildProblems
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters.DependencyBuildProblem
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters.ModuleDependencyWithOverriddenVersion
import org.jetbrains.amper.frontend.messages.computeRange
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.LineAndColumn
import org.jetbrains.amper.problems.reporting.LineAndColumnRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Ignore
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsTest : AbstractDependencyInsightsTest() {

    override val testGoldenFilesRoot: Path
        get() = super.testGoldenFilesRoot.resolve("diagnostics")

    @Test
    fun `test sync diagnostics`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("multi-module-failed-resolve", testDataRoot)

        assertEquals(
            setOf("common", "commonTest", "jvm", "jvmTest"),
            aom.modules.single { it.userReadableName == "shared" }.fragments.map { it.name }.toSet(),
            ""
        )

        val sharedTestFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "shared",
            filter = ideSyncModuleResolutionFilter,
            messagesCheck = { node ->
                if (!assertDependencyError(node, "org.jetbrains.compose.foundation", "foundation")
                    && !assertDependencyError(node, "org.jetbrains.compose.ui", "ui")
                    && !assertDependencyError(node, "org.jetbrains.compose.runtime", "runtime")
                    && !assertDependencyError(node, "org.jetbrains.compose.components", "components-resources")
                    && !assertDependencyError(node, "org.jetbrains.kotlinx", "kotlinx-serialization-core")
                ) {
                    node.verifyOwnMessages()
                }
            }
        )

        assertFiles(
            testInfo,
            root = sharedTestFragmentDeps,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(sharedTestFragmentDeps, diagnosticsReporter, Level.Error)
        val buildProblems = diagnosticsReporter.problems

        /**
         * This magic number 20 (4*5) appears because we are diagnosing each fragment (4 fragments total),
         * and each fragment contains 5 incorrect dependencies.
         *
         * The common fragment contains incorrect dependencies by definition in a module file.
         * More specific fragments contain incorrect dependencies because they were propagated during merge.
         */
        assertEquals(20, buildProblems.size)

        // Direct dependency on a built-in library,
        // A version of the library is taken from settings:compose:version in file module.yaml
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.compose.foundation", "foundation",
            Path("module.yaml"), 16, 14
        )
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.compose.ui", "ui",
            Path("module.yaml"), 16, 14
        )

        // Implicit dependency added by `compose: enabled`
        // A version of the library is taken from settings:compose:version in file module.yaml
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.compose.runtime", "runtime",
            Path("module.yaml")
        )
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.compose.components", "components-resources",
            Path("module.yaml")
        )

        // Implicit dependency added by `serialization: enabled`
        // A version of the library is taken from settings:serialization:version in file template.yaml
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.kotlinx", "kotlinx-serialization-core",
            Path("..") / "templates" / "template.yaml",
            5, 16
        )
    }

    /**
     * The test checks that implicitly added kotlin-related dependencies with invalid versions are correctly reported
     */
    @Test
    fun `test sync diagnostics kotlib stdlib`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("multi-module-failed-resolve-kotlin-stdlib", testDataRoot)

        assertEquals(
            setOf("main", "test"),
            aom.modules.single { it.userReadableName == "app" }.fragments.map { it.name }.toSet(),
            ""
        )

        val jvmAppFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "app",
            filter = ideSyncModuleResolutionFilter,
            messagesCheck = { node ->
                if (!assertDependencyError(node, "org.jetbrains.kotlin", "kotlin-stdlib")
                    && !assertDependencyError(node, "org.jetbrains.kotlin", "kotlin-test-junit5")
                ) {
                    node.verifyOwnMessages()
                }
            }
        )

        assertFiles(
            testInfo,
            root = jvmAppFragmentDeps,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(jvmAppFragmentDeps, diagnosticsReporter, Level.Error)
        val buildProblems = diagnosticsReporter.problems

        // Implicit dependency added by `kotlin`
        // A version of the library is taken from settings:kotlin:version in file module.yaml
        checkBuiltInDependencyBuildProblem(
            buildProblems, 4,
            "org.jetbrains.kotlin", "kotlin-stdlib",
            Path("module.yaml"),
        )

        // Implicit dependency added by `kotlin`
        // A version of the library is taken from settings:kotlin:version in file module.yaml
        checkBuiltInDependencyBuildProblem(
            buildProblems, 2,
            "org.jetbrains.kotlin", "kotlin-test-junit5",
            Path("module.yaml"),
        )

        /**
         * This magic number 6 (2*3) doesn't matter much on its own, what matters is that
         * all buildProblems belong to the dependencies "kotlin-stdlib" and "kotlin-test-junit5" and are checked above
         */
        assertEquals(6, buildProblems.size)
    }

    /**
     * AMPER-4882 revealed that the dependency insights graph is calculated for the same coordinates as many times as
     * coordinates are mentioned in the AOM
     * (i.e., the number of times dependency is added to project modules multiplied by the number
     * of resolution contexts (platform and scope)).
     *
     * This test checks that the dependency insights graph is calculated the same number of times as the quantity of
     * different coordinates with an overridden version
     * (one coordinates with an overridden version => one dependency insights graph)
     */
    @Test
    fun `dependency insights is calculated no more than needed for problematic dependency`(testInfo: TestInfo) =
        runSlowModuleDependenciesTest {
            val aom = getTestProjectModel("multuplatform-with-overridden-versions", testDataRoot)

            val projectDeps = doTestByFile(
                testInfo,
                aom,
                ideSyncTestResolutionInput,
                messagesCheck = { node ->
                    assertTrue {
                        node.messages.all { it.severity <= Severity.WARNING }
                    }
                },
                filter = ideSyncModuleResolutionFilter
            )

            assertFiles(testInfo,projectDeps)

            val diagnosticsReporter = CollectingProblemReporter()
            collectBuildProblems(projectDeps, diagnosticsReporter, Level.Warning)
            val buildProblems = diagnosticsReporter.problems

            /**
             * This magic number doesn't matter on its own.
             * What matters is that all warnings are related to overridden dependencies (next check).
             */
            assertEquals(20, buildProblems.size)

            val overriddenDependencyProblems = buildProblems.mapNotNull { it as? ModuleDependencyWithOverriddenVersion }
            assertEquals(buildProblems.size, overriddenDependencyProblems.size)

            val problematicDependencies = overriddenDependencyProblems.map { it.dependencyNode.key }.distinct()
            assertEquals(
                setOf(
                    "org.jetbrains.compose.runtime:runtime"
                ),
                problematicDependencies.map { it.name }.toSet()
            )

            val uniqueInsights = overriddenDependencyProblems.map { it.overrideInsight }.distinct()
            assertEquals(
                3, uniqueInsights.size,
                "Insights were calculated unexpected number of times, " +
                        "while calculation of the single insight pr module" +
                        "for the library 'org.jetbrains.compose.runtime:runtime' is expected"
            )
        }

    /**
     * Migration to module-wide resolution lead to calculating overridden diagnostic of the dependency
     * as many times as the number of modules referencing it multiplied twice (main/test) instead of a single
     * calculation in the project-wide resolution.
     * That caused performance degradation.
     *
     * This test checks that the dependency insights graph calculation doesn't impact performance much
     * and that diagnostic is calculated the same number of times as the quantity of
     * different modules the dependency is referenced from (multiplied for main/test).
     *
     * // todo (AB) : Fix degradation and remove @Ignore
     */
    @Test
    @Ignore
    fun `dependency insights is calculated no more than needed and doesn't affect performance`(testInfo: TestInfo) =
        runSlowModuleDependenciesTest {
            val aom = getTestProjectModel("multiplatfrorm-large-with-overridden-versions", testDataRoot)

            val projectDeps = timed("doTestByFile") {
                doTestByFile(
                    testInfo,
                    aom,
                    ideSyncTestResolutionInput,
                    messagesCheck = { node ->
                        assertTrue {
                            node.messages.all { it.severity <= Severity.WARNING }
                        }
                    },
                    filter = ideSyncModuleResolutionFilter
                )
            }

            assertFiles(testInfo,projectDeps)

            val diagnosticsReporter = CollectingProblemReporter()
            timed("collectBuildProblems") {
                collectBuildProblems(projectDeps, diagnosticsReporter, Level.Warning)
            }
            val buildProblems = diagnosticsReporter.problems

            /**
             * This magic number doesn't matter on its own.
             * What matters is that all warnings are related to overridden dependencies (next check).
             */
            assertEquals(189, buildProblems.size)

            val overriddenDependencyProblems = buildProblems.mapNotNull { it as? ModuleDependencyWithOverriddenVersion }
            assertEquals(buildProblems.size, overriddenDependencyProblems.size)

            val problematicDependencies = overriddenDependencyProblems.map { it.dependencyNode.key }.distinct()
            assertEquals(
                setOf(
                    "org.jetbrains.compose.foundation:foundation",
                    "androidx.activity:activity-compose",
                    "androidx.appcompat:appcompat",
                    "org.jetbrains.compose.runtime:runtime"
                ),
                problematicDependencies.map { it.name }.toSet()
            )

            // todo (AB) : Perform experiment: check how much time resolution of insight is taken in this branch and in main
            //   for 'multiplatfrorm-large-with-overridden-versions' project

            val uniqueInsights = overriddenDependencyProblems
                .filter{ it.dependencyNode.key.name == "org.jetbrains.compose.runtime:runtime" }
                .map { it.overrideInsight }.distinct()
            assertEquals(
                9, uniqueInsights.size,
                "Insights were calculated unexpected number of times, " +
                        "while calculation of the single insight pr module" +
                        "for the library 'org.jetbrains.compose.runtime:runtime' is expected"
            )
        }


    // AMPER-4270
    @Test
    fun `overridden version for BOM version is not displayed for unspecified versions`() = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-bom-support", testDataRoot)
        val mainFragmentDeps = doTest(
            aom,
            ideSyncTestResolutionInput,
            module = "app",
            fragment = "main",
            expected = """
                Module app
                │ - main
                │ - scope = COMPILE
                │ - platforms = [jvm]
                ├─── app:main:com.fasterxml.jackson.core:jackson-annotations:unspecified
                │    ╰─── com.fasterxml.jackson.core:jackson-annotations:unspecified -> 2.18.3
                │         ╰─── com.fasterxml.jackson:jackson-bom:2.18.3
                │              ╰─── com.fasterxml.jackson.core:jackson-annotations:2.18.3 (c)
                ├─── app:main:com.fasterxml.jackson:jackson-bom:2.18.3
                │    ╰─── com.fasterxml.jackson:jackson-bom:2.18.3 (*)
                ╰─── app:main:org.jetbrains.kotlin:kotlin-stdlib:${DefaultVersions.kotlin}, implicit
                     ╰─── org.jetbrains.kotlin:kotlin-stdlib:${DefaultVersions.kotlin}
                          ╰─── org.jetbrains:annotations:13.0
            """.trimIndent(),
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
            verifyMessages = false,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(mainFragmentDeps, diagnosticsReporter, Level.Warning)
        val buildProblems = diagnosticsReporter.problems

        assertEquals(
            0, buildProblems.size,
            "No problems should be reported for JVM deps, but got the following ${buildProblems.size} problem(s):\n${
                buildProblems.joinToString(
                    "\n"
                ) { it.message }
            }"
        )
    }

    /**
     * This test checks that WARNING is reported in case a direct dependency version was unspecified and
     * taken from BOM, but later was overridden due to conflict resolution.
     *
     * Test checks COMPILE classpath.
     * Version of `io.ktor:ktor-client-cio-jvm` got overridden
     * despite compile classpath of module 'A' doesn't contain dependencies of module 'B',
     * because versions of COMPILE and RUNTIME dependencies are aligned.
     */
    @Test
    fun `overridden version for unspecified version resolved from BOM is detected`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-bom-support-override", testDataRoot)
        val commonDeps = doTestByFile(
            testInfo = testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "app",
            fragment = "main",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
            verifyMessages = false,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(commonDeps, diagnosticsReporter, Level.Warning)

        val buildProblem = diagnosticsReporter.problems.singleOrNull()

        assertNotNull (
            buildProblem,
            "One build problem should be reported for dependency 'io.ktor:ktor-client-cio-jvm', " +
                    "but got the following ${diagnosticsReporter.problems.size} problem(s):\n${
                        diagnosticsReporter.problems.joinToString("\n") { it.message }
                    }"
        )

        buildProblem as ModuleDependencyWithOverriddenVersion

        assertEquals(buildProblem.dependencyNode.key.name, "io.ktor:ktor-client-cio-jvm",
            "Build problem is reported for unexpected dependency")
        assertEquals(buildProblem.level, Level.Warning, "Unexpected build problem level")

        assertNull(buildProblem.dependencyNode.originalVersion, "Original version should be left unspecified")
        assertEquals(buildProblem.dependencyNode.versionFromBom, "3.0.2", "Incorrect version resolved from BOM")
        assertEquals(buildProblem.message,
            "Version 3.0.2 of dependency io.ktor:ktor-client-cio-jvm taken from BOM is overridden, the actual version is 3.1.2.",
            "Unexpected diagnostic message"
        )
    }

    /**
     * This test checks that WARNING is reported in case a direct dependency version was unspecified and
     * taken from BOM, but later was overridden due to conflict resolution.
     *
     * Test checks COMPILE classpath.
     * Version of `io.ktor:ktor-client-cio-jvm` got overridden
     * despite compile classpath of module 'A' doesn't contain dependencies of module 'B',
     * because versions of COMPILE and RUNTIME dependencies are aligned.
     */
    @Test
    fun `overridden version specified in unrelated project does not affect unspecified version resolved from BOM`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-bom-support-no-override", testDataRoot)
        val commonDeps = doTestByFile(
            testInfo = testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "app",
            fragment = "main",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.RUNTIME),
            verifyMessages = false,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(commonDeps, diagnosticsReporter, Level.Warning)

        val buildProblem = diagnosticsReporter.problems.singleOrNull()

        assertNull(
            buildProblem,
            "No problem should be reported for dependency 'io.ktor:ktor-client-cio-jvm'," +
                    " since it is overridden in unrelated module"
        )
    }

    @Test
    fun `overridden version for Kotlin stdlib is detected`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("kotlin-stdlib-override", testDataRoot)
        val deps = doTestByFile(
            testInfo = testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "kotlin-stdlib-override",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
            verifyMessages = false,
        )

        val diagnosticsReporter = CollectingProblemReporter()
        collectBuildProblems(deps, diagnosticsReporter, Level.Warning)

        val buildProblems = diagnosticsReporter.problems
            .filterIsInstance<ModuleDependencyWithOverriddenVersion>()
            .distinctBy { it.message }
        assertEquals(
            1,
            buildProblems.size,
            "One build problem should be reported for Kotlin stdlib, but got the following ${buildProblems.size} problem(s):\n" +
                    buildProblems.joinToString("\n") { it.message }
        )
        val buildProblem = buildProblems.single()
        assertEquals(
            "org.jetbrains.kotlin:kotlin-stdlib",
            buildProblem.dependencyNode.key.name,
            "Expected override on Kotlin stdlib dependency, but got it on ${buildProblem.dependencyNode.key.name}"
        )
        val source = buildProblem.source
        assertInstanceOf<FileWithRangesBuildProblemSource>(source)
        assertEquals(
            expected = LineAndColumnRange(
                LineAndColumn(7, 14),
                LineAndColumn(7, 20),
            ),
            actual = source.computeRange(),
            message = "Unexpected source range for build problem. The Kotlin version value should be highlighted."
        )
    }


    @OptIn(ExperimentalContracts::class)
    internal fun DependencyNode.isMavenDependency(group: String, module: String): Boolean {
        contract {
            returns(true) implies (this@isMavenDependency is MavenDependencyNode)
        }
        return this is MavenDependencyNode && this.group == group && this.module == module
    }

    private fun assertDependencyError(node: DependencyNode, group: String, module: String): Boolean {
        if (node.isMavenDependency(group, module)) {
            assertEquals(
                setOf("Unable to resolve dependency ${node.dependency.group}:${node.dependency.module}:${node.dependency.version.orUnspecified()}"),
                node.messages.map { it.message }.toSet()
            )
            return true
        }
        return false
    }

    private fun checkBuiltInDependencyBuildProblem(
        buildProblems: Collection<BuildProblem>,
        expectedProblemsCount: Int,
        group: String, module: String,
        filePath: Path,
        versionLineNumber: Int? = null, versionColumn: Int? = null
    ) {
        val relevantBuildProblems = buildProblems.filter {
            it is DependencyBuildProblem
                    && it.problematicDependency.isMavenDependency(group, module)
        }

        assertEquals(expected = expectedProblemsCount, actual = relevantBuildProblems.size,
            "Unexpected number of build problems for $group:$module")

        relevantBuildProblems.forEach { buildProblem ->
            buildProblem as DependencyBuildProblem
            val mavenDependency = (buildProblem.problematicDependency as MavenDependencyNode).dependency

            assertContains(
                buildProblem.message,
                "Unable to resolve dependency ${mavenDependency.group}:${mavenDependency.module}:${mavenDependency.version.orUnspecified()}"
            )
            if (versionLineNumber != null) {
                assertContains(
                    buildProblem.message,
                    "The version ${mavenDependency.version} is defined at $filePath:$versionLineNumber:$versionColumn"
                )
            } else {
                assertFalse(buildProblem.message.contains("The version ${mavenDependency.version} is defined at"))
            }
        }
    }
}
