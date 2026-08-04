/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.filterGraph
import org.junit.jupiter.api.TestInfo

open class AbstractDependencyInsightsTest : BaseModuleDrTest() {

    protected suspend fun assertInsightByFile(group: String, module: String, graph: DependencyNode, testInfo: TestInfo) {
        val insightFilePrefix = "${testInfo.testMethod.get().name}.$module".replace(" ", "_")

        val goldenFileResolvedInsights = goldenFileOsArchAware("$insightFilePrefix.insight.resolved.txt")
        val expectedResolved = getGoldenFileText(goldenFileResolvedInsights, fileDescription = "Golden file with insight for resolved version only")
        withActualDumpAndDelayedAssertion(expectedResultPath = goldenFileResolvedInsights) {
            assertInsight(group, module, graph, expectedResolved, resolvedVersionOnly = true)
        }

        val goldenFileFull = goldenFileOsArchAware("$insightFilePrefix.insight.full.txt")
        val expectedFull = getGoldenFileText(goldenFileFull, fileDescription = "Golden file with full insight")
        withActualDumpAndDelayedAssertion(expectedResultPath = goldenFileFull) {
            assertInsight(group, module, graph, expectedFull, resolvedVersionOnly = false)
        }

        val goldenFileOriginalGraph = goldenFileOsArchAware("$insightFilePrefix.insight.originalGraph.txt")
        val expectedGraph = getGoldenFileText(goldenFileOriginalGraph, fileDescription = "Golden file with full dependency graph")
        withActualDumpAndDelayedAssertion(expectedResultPath = goldenFileOriginalGraph) {
            assertModuleDepsEquals(expectedGraph, graph, null)
        }
    }

    protected fun assertInsight(group: String, module: String, graph: DependencyNode, expected: String, resolvedVersionOnly: Boolean = false) {
        val subGraph = timedBlocking(
            "dependencyInsight [$group:$module, ${if(resolvedVersionOnly) "resolvedVersionOnly" else "full"}]"
        ) {
            graph.filterGraph(group, module, resolvedVersionOnly)
        }
        assertModuleDepsEquals(expected, subGraph, MavenCoordinates(group, module, null))
    }
}
