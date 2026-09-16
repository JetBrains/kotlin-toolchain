/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests that conflict resolution converges: once a graph is resolved, all its nodes sharing the same `group:module`
 * must point to the very same version.
 */
class ConflictResolutionTest : BaseDRTest() {

    override val testDataPath: Path = super.testDataPath / "conflictResolution"

    /**
     * This test checks that dependency resolution with detected conflicts produces the same reproducible and stable result.
     * It runs several iterations in order to increase the probability of failure if the code is broken and
     * dependency resolution result is flaky.
     */
    @Test
    @Tag("gold-file")
    fun `compose ios app resolves to a single version per module`(testInfo: TestInfo) = runSlowDrTest {
        repeat(RESOLUTION_ATTEMPTS) { attempt ->
            val root = resolveComposeIosApp()
            root.assertStillReproducesALateConflict()
            root.assertSingleVersion(attempt)
        }

        // The golden file pins the whole expected classpath: an unaligned node shows up there as an extra artifact of
        // the same module in a different version.
        downloadAndAssertFiles(testInfo, resolveComposeIosApp())
    }

    /**
     * Unlike the test above, which checks the user-visible outcome that a broken conflict resolution only produces
     * *sometimes*, this test checks the underlying deterministic invariant.
     *
     * The invariant: between two resolution waves, every node that is a part of the graph must be taken into account
     * by the conflict resolver, because a node that IS in the graph but IS NOT a conflict candidate (contained by [ConflictResolver.similarNodesByKey])
     * can never be aligned with a conflict winner anymore.
     *
     * This test was added as a part of the fix of the conflict resolver issue:
     * Before the fix, conflict resolver detached the losing subgraphs and forgot their nodes
     * (removing them from [ConflictResolver.similarNodesByKey]), while some of those very nodes were attached back
     * to the graph under the winning subgraph in a later wave, with nothing registering them back as conflict candidates.
     * Such a node kept the version it was originally requested with, because no conflict involving it could be detected anymore.
     */
    @Test
    fun `every node of the graph is registered as a conflict candidate between waves`() = runSlowDrTest {
        val violations = mutableListOf<String>()
        // A dangling node can also be picked up again within the very same wave, in which case this resolution
        // happens to keep the invariant. A handful of resolutions makes sure we don't rely on that luck.
        repeat(RESOLUTION_ATTEMPTS) { attempt ->
            violations += resolveCollectingRegistryViolations(attempt)
        }

        assertTrue(
            violations.isEmpty(),
            "Nodes of the resolved graph were left out of conflict resolution:\n" + violations.joinToString("\n")
        )
    }

    private suspend fun resolveComposeIosApp(): DependencyNodeHolderWithContext =
        composeIosAppRootNode().also { Resolver().buildGraph(it, ResolutionLevel.NETWORK) }

    private suspend fun resolveCollectingRegistryViolations(attempt: Int): List<String> {
        val root = composeIosAppRootNode()
        val violations = mutableListOf<String>()
        var wave = 0
        val resolver = Resolver(ConflictResolutionWaveObserver { waveRoot, registeredNodes ->
            val danglingNodes = waveRoot.distinctBfsSequence().filter { it !in registeredNodes }.toList()
            if (danglingNodes.isNotEmpty()) {
                violations += "attempt #$attempt, wave #$wave: ${danglingNodes.size} node(s) in the graph are not " +
                        "registered as conflict candidates: ${danglingNodes.joinToString { it.describe() }}"
            }
            wave++
        })

        resolver.buildGraph(root, ResolutionLevel.NETWORK)
        root.assertStillReproducesALateConflict()
        return violations
    }

    private fun composeIosAppRootNode(): DependencyNodeHolderWithContext =
        COMPOSE_IOS_APP_DEPENDENCIES.toRootNode(
            context(
                platform = setOf(ResolutionPlatform.IOS_SIMULATOR_ARM64),
                repositories = listOf(REDIRECTOR_MAVEN_CENTRAL, REDIRECTOR_MAVEN_GOOGLE),
            )
        )

    private fun DependencyNode.assertSingleVersion(attempt: Int) {
        val unalignedModules = distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .groupBy { "${it.group}:${it.module}" }
            .mapValues { entry -> entry.value.mapNotNullTo(mutableSetOf()) { node -> node.dependency.version } }
            .filterValues { versions -> versions.size > 1 }

        assertTrue(
            unalignedModules.isEmpty(),
            "Conflict resolution didn't converge on attempt #$attempt, several versions of the same module are left " +
                    "in the resolved graph: " +
                    unalignedModules.entries.joinToString { "${it.key} -> ${it.value}" }
        )
    }

    /**
     * Guards the test itself: this scenario only exercises conflict resolution as long as
     * [COMPOSE_IOS_APP_DEPENDENCIES] really do request several versions of [CONFLICTING_MODULE]. Should the published
     * versions converge one day, these tests would keep passing without checking anything, so they fail loudly
     * instead.
     */
    private fun DependencyNode.assertStillReproducesALateConflict() {
        val requestedVersions = distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .filter { "${it.group}:${it.module}" == CONFLICTING_MODULE }
            .mapNotNullTo(mutableSetOf()) { it.originalVersion }

        assertTrue(
            requestedVersions.size > 1,
            "These tests rely on several versions of '$CONFLICTING_MODULE' being requested across the graph of " +
                    "${COMPOSE_IOS_APP_DEPENDENCIES.joinToString()}, but found $requestedVersions. The published " +
                    "versions have probably converged, so the dependencies above have to be adjusted to request " +
                    "conflicting versions of a module that is only reached deep in the graph again."
        )
    }

    private fun DependencyNode.describe(): String = when (this) {
        is MavenDependencyNode -> "$group:$module:$originalVersion (resolved to ${dependency.version})"
        else -> key.name
    }

    private companion object {
        const val RESOLUTION_ATTEMPTS = 5

        /** The module whose conflicting versions these tests are built around. */
        const val CONFLICTING_MODULE = "androidx.savedstate:savedstate-compose"

        /**
         * `material-icons-core:1.7.3` depends on `androidx.savedstate:savedstate-compose:1.3.3`,
         * while the newer Compose artifacts depend on `androidx.savedstate:savedstate-compose:1.4.0` transitively.
         * The older version is only discovered deep in the graph, in a later resolution wave, which is what triggers the
         * detach/attach-back sequence these tests are about.
         */
        val COMPOSE_IOS_APP_DEPENDENCIES = listOf(
            "org.jetbrains.compose.components:components-resources:1.11.1",
            "org.jetbrains.compose.material3:material3:1.11.0-alpha07",
            "org.jetbrains.compose.runtime:runtime:1.11.1",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",

            "org.jetbrains.compose.material:material-icons-core:1.7.3",
        )
    }
}
