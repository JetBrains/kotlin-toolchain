/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.gradle.module.metadata.format.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertSame

class GraphOptimizationsTest : BaseDRTest() {

    @Test
    fun `MavenDependencyNode key is calculated once` () {
        val node = MavenDependencyNodeWithContext(
            Context {},
            MavenCoordinates("group", "module", "1.0.0"),
            false
        )
        node.checkKey("group:module")

        val serializedNode = node.toGraph().root
        serializedNode.checkKey("group:module")
    }

    @Test
    fun `MavenDependencyConstraintNode key is calculated once` () {
        val node = MavenDependencyConstraintNodeWithContext(
            Context {},
            MavenDependencyConstraintImpl("group", "module", Version(requires = "1.0.0"))
        )
        node.checkKey("group:module")

        val serializedNode = node.toGraph().root
        serializedNode.checkKey(node.key.name)
    }

    @Test
    fun `graph entry index is appended to, not rebuilt, on registration` () {
        val graphContext = DependencyGraphContext()

        val first = resolutionConfig(ResolutionScope.COMPILE, ResolutionPlatform.JVM)
        first.toSerializableReference(graphContext)
        val index = graphContext.allResolutionConfigsList

        val second = resolutionConfig(ResolutionScope.RUNTIME, ResolutionPlatform.ANDROID)
        second.toSerializableReference(graphContext)

        // Rebuilding an index from the map keys on every registration makes graph conversion quadratic
        // in the number of graph entries, which takes hours on large projects.
        assertSame(index, graphContext.allResolutionConfigsList, "Index should not be rebuilt on registration")
        assertEquals(listOf(first, second), graphContext.allResolutionConfigsList)
        assertEquals(first, graphContext.getResolutionConfig(0))
        assertEquals(second, graphContext.getResolutionConfig(1))
    }

    @Test
    fun `graph entry index is rebuilt when graph entries are added in bulk` () {
        val graphContext = DependencyGraphContext()
        val configs = listOf(
            resolutionConfig(ResolutionScope.COMPILE, ResolutionPlatform.JVM),
            resolutionConfig(ResolutionScope.RUNTIME, ResolutionPlatform.ANDROID),
        )

        // Graph deserialization fills the maps directly, bypassing the 'register*' methods.
        configs.forEachIndexed { index, config -> graphContext.allResolutionConfigs[config] = index }

        assertEquals(configs, graphContext.allResolutionConfigsList)
        assertSame(configs[0], graphContext.getResolutionConfig(0))
        assertSame(configs[1], graphContext.getResolutionConfig(1))
    }

    private fun resolutionConfig(scope: ResolutionScope, platform: ResolutionPlatform) =
        ResolutionConfigPlain(scope, setOf(platform), listOf(REDIRECTOR_MAVEN_CENTRAL.url))

    private fun DependencyNode.checkKey(keyName: String) {
        assertEquals(keyName, key.name)
        assertSame(key, key, "Key instance should not be recalculated on every access")
    }
}