/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * AI disclosure: AI generated this test while it was investigating
 * flaky behavior, leading to several nodes of different versions being added in the dependency graph.
 * It might be useful for later investigations.
 *
 * Conflict resolution must leave a single version of every library in the resolved graph: the conflict key is
 * `"$group:$module"` (see `getKey`), which is version-independent.
 *
 * This matters beyond the graph itself, because `dependencyPaths` collects the files of every *reachable*
 * `MavenDependencyNode` into a plain set of paths, without collapsing versions. A node of a losing version that stays
 * reachable therefore puts its artifact on the compilation classpath next to the winner's, and for Kotlin/Native that
 * surfaces much later as a cryptic compiler error, because both klibs carry the same `unique_name`:
 * ```
 * error: KLIB resolver: The same 'unique_name=androidx.savedstate:savedstate-compose' found in more than one library:
 *   …/savedstate-compose-iossimulatorarm64/1.3.3/savedstate-compose-iossimulatorarm64-1.3.3.klib,
 *   …/savedstate-compose-iossimulatorarm64/1.4.0/savedstate-compose-iossimulatorarm64-1.4.0.klib
 * ```
 *
 * The dependency set below is the one that produced that error on CI, and it has the shape that exposes the bug: a KMP
 * library is split into a root node (which carries the conflict) and a platform-specific child (which carries the
 * klib), and here the requester of the losing version sits *underneath* the winning subgraph. Both levels were left
 * behind at two versions at once:
 * ```
 *   androidx.savedstate:savedstate-compose -> 1.3.3, 1.4.0
 *   androidx.savedstate:savedstate-compose-iossimulatorarm64 -> 1.3.3, 1.4.0
 * ```
 *
 * ### The bug this covers
 *
 * `ConflictResolver.registerAndDetectConflictsWithChildren` used to skip the whole subtree when the node it was given
 * was already registered. That premise doesn't hold: registration is per node, children are swapped as conflicts get
 * resolved, and `unregisterOrphanNodes` drops individual nodes from within a subtree. Nodes missed that way were never
 * registered at all, so their key never reached two candidates, no conflict was ever detected for it, and they kept
 * their losing version. Instrumenting the resolver while the failure was reproducing showed exactly that for the
 * platform-specific node: no conflict had ever been flagged for its key, and the node was absent from the conflict
 * resolver, while its sibling at the winning version was present.
 *
 * ### Reproducing it
 *
 * Whether the bug fires depends on how the concurrent resolution interleaves, not on the contents of the file cache
 * (it was seen with a cache byte-identical to one that resolved cleanly). Limiting the JVM to **2 or 3 processors**
 * reproduced it consistently, while 1, 4, 6 and 8 never did — so a plain run of this test is a weak guard, and the
 * thread count is the knob that matters:
 * ```
 * ./kotlin test -m dependency-resolution \
 *     --include-classes org.jetbrains.amper.dependency.resolution.ConflictResolutionStressTest \
 *     --jvm-args="-XX:ActiveProcessorCount=2 -Ddr.stress.iterations=25 -Ddr.stress.parallelism=3"
 * ```
 * Resolutions run on [kotlinx.coroutines.Dispatchers.Default] (see `runTestRespectingDelays`), so they are genuinely
 * concurrent; `dr.stress.parallelism` adds independent resolutions on top of that to vary the interleavings, and
 * `dr.stress.coldCache` swaps the shared cache for an empty temporary one (populated by the first iteration).
 */
class ConflictResolutionStressTest : BaseDRTest() {

    /**
     * The direct compile dependencies of the `shared` module of the `compose-resources-demo` test project,
     * as resolved for `iosSimulatorArm64`.
     *
     * The pinned `material-icons-core` is what drags in the older generation of the androidx/Compose graph
     * ("this is no longer updated, but we need it here for the demo's sake"), so that two generations of
     * `androidx.savedstate:savedstate-compose` are requested at once.
     */
    private val composeResourcesDemoDependencies = listOf(
        "org.jetbrains.compose.material3:material3:1.11.0-alpha07",
        "org.jetbrains.compose.material:material-icons-core:1.7.3",
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.21",
        "org.jetbrains.compose.runtime:runtime:1.11.1",
        "org.jetbrains.compose.components:components-resources:1.11.1",
    )

    @TempDir
    lateinit var coldCacheRoot: Path

    @Test
    @Ignore
    fun `resolved graph holds a single version of every library`() = runSlowDrTest(timeout = 30.minutes) {
        val iterations = intProperty("dr.stress.iterations", default = 3)
        val parallelism = intProperty("dr.stress.parallelism", default = 1)
        // Whether to start from an empty cache (the first iteration then downloads everything, the later ones read
        // from disk) instead of reusing the shared test cache, whose contents depend on whatever ran before.
        val coldCache = System.getProperty("dr.stress.coldCache").toBoolean()

        var completed = 0
        while (completed < iterations) {
            val batch = minOf(parallelism, iterations - completed)
            coroutineScope {
                val cacheRoot = if (coldCache) coldCacheRoot else Dirs.userCacheRoot
                (0 until batch).map { async { resolveComposeResourcesDemo(cacheRoot) } }.awaitAll()
            }.forEachIndexed { indexInBatch, root ->
                val iteration = completed + indexInBatch
                root.assertResolvedTheLibraryUnderTest(iteration)
                root.assertSingleVersionPerLibraryInGraph(iteration)
                root.assertSingleVersionPerLibrary(iteration)
            }
            completed += batch
        }
    }

    private suspend fun resolveComposeResourcesDemo(cacheRoot: Path): DependencyNode {
        val root = composeResourcesDemoDependencies.toRootNode(
            context(
                scope = ResolutionScope.COMPILE,
                platform = setOf(ResolutionPlatform.IOS_SIMULATOR_ARM64),
                repositories = listOf(REDIRECTOR_MAVEN_CENTRAL, REDIRECTOR_MAVEN_GOOGLE, REDIRECTOR_COMPOSE_DEV),
                cacheBuilder = cacheBuilder(cacheRoot),
            )
        )
        Resolver().buildGraph(root, ResolutionLevel.NETWORK)
        return root
    }

    /**
     * Guards the test itself: if the graph were not resolved (or resolved into something unrelated), every assertion
     * below would hold vacuously.
     */
    private fun DependencyNode.assertResolvedTheLibraryUnderTest(iteration: Int) {
        val savedStateNodes = distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .filter { it.dependency.coordinates.artifactId.startsWith("savedstate-compose") }
            .toList()
        assertTrue(
            actual = savedStateNodes.isNotEmpty(),
            message = "Iteration $iteration resolved a graph without any 'savedstate-compose' node, " +
                    "so it cannot exercise the conflict this test is about",
        )
    }

    /**
     * Asserts the invariant conflict resolution is supposed to establish: no two *reachable* nodes resolve the same
     * library to different versions.
     *
     * Nodes whose conflict was resolved don't count — they end up carrying the winning version, only their
     * `originalVersion` still records what they asked for.
     *
     * Unlike [assertSingleVersionPerLibrary], this doesn't depend on which artifacts happen to be in the file cache,
     * so it states the bug itself rather than the conditions under which the bug becomes visible to the compiler.
     */
    private fun DependencyNode.assertSingleVersionPerLibraryInGraph(iteration: Int) {
        val versionsByLibrary = mutableMapOf<String, MutableSet<String?>>()
        distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .forEach { node ->
                val coordinates = node.dependency.coordinates
                versionsByLibrary.getOrPut("${coordinates.groupId}:${coordinates.artifactId}") { mutableSetOf() } +=
                    coordinates.version
            }

        val clashing = versionsByLibrary.filterValues { it.size > 1 }

        assertTrue(
            actual = clashing.isEmpty(),
            message = "Iteration $iteration left several versions of the same library in the resolved graph:\n" +
                    clashing.entries.joinToString("\n") { [library, versions] ->
                        "  $library -> ${versions.sortedBy { it.orEmpty() }.joinToString()}"
                    },
        )
    }

    /**
     * Asserts that no two reachable nodes contribute files for the same library at different versions, which is
     * exactly the state the Kotlin/Native compiler rejects.
     *
     * Note this can only see a leftover node once its artifact has actually been downloaded: `DependencyFile.path` is
     * null for a file that isn't in the cache, and [DependencyNode.dependencyPaths] skips those. That is a second
     * condition on top of the interleaving one, and it is why a broken resolution doesn't always reach the compiler:
     * the losing version's `.module`/`.pom` may sit in the cache without its `.klib`, in which case the graph is wrong
     * but the build still succeeds. See [assertSingleVersionPerLibraryInGraph] for the check that doesn't depend on it.
     *
     * Files are grouped by `"$group:$module"` (the conflict key) rather than by path, so that several artifacts of the
     * same library at the same version (different classifiers, for instance) are not mistaken for a conflict.
     */
    private fun DependencyNode.assertSingleVersionPerLibrary(iteration: Int) {
        val contributorsByLibrary = mutableMapOf<String, MutableSet<Pair<MavenDependencyNode, Path>>>()
        distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .forEach { node ->
                val coordinates = node.dependency.coordinates
                val library = "${coordinates.groupId}:${coordinates.artifactId}"
                node.dependency.files()
                    .mapNotNull { it.path }
                    .forEach { contributorsByLibrary.getOrPut(library) { mutableSetOf() } += node to it }
            }

        val clashing = contributorsByLibrary
            .filterValues { contributors -> contributors.mapTo(mutableSetOf()) { it.first.dependency.coordinates.version }.size > 1 }

        assertTrue(
            actual = clashing.isEmpty(),
            message = "Iteration $iteration resolved several versions of the same library into the classpath:\n" +
                    clashing.entries.joinToString("\n") { [library, contributors] ->
                        "  $library\n" + contributors.sortedBy { it.second }.joinToString("\n") { [node, path] ->
                            "    $path\n" +
                                    "      reachable from: ${node.parents.joinToString() { it.graphEntryName }}"
                        }
                    },
        )
    }

    private fun intProperty(name: String, default: Int): Int =
        System.getProperty(name)?.toIntOrNull() ?: default
}
