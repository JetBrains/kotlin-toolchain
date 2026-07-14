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

    private fun DependencyNode.checkKey(keyName: String) {
        assertEquals(keyName, key.name)
        assertSame(key, key, "Key instance should not be recalculated on every access")
    }
}