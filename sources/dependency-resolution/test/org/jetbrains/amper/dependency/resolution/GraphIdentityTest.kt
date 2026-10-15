/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.gradle.module.metadata.format.Version
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GraphIdentityTest : BaseDRTest() {

    /**
     * Checks that the same graph constraints [MavenDependencyConstraintPlain] are stored once in serialized form
     * (there is no need to waste storage space for them, since there is no need to distinguish those by instances)
     */
    @Test
    fun `constraints equal by content share an entry after round trip`() {
        val restored = constraintNode().toGraph().roundTrip().root
        val root = RootDependencyNodeStub(children = [restored, constraintNode(), constraintNode()])

        val constraints = root.toGraph().roundTrip().root.children
            .map { (it as MavenDependencyConstraintNode).dependencyConstraint }

        assertEquals(3, constraints.size)
        assertEquals(MavenDependencyConstraintPlain("group", "module", Version(requires = "1.0.0")), constraints[0])
        constraints.forEach { assertSame(constraints[0], it, "Constraints equal by content should share a single graph entry") }
    }

    /**
     * Checks that different maven dependencies [MavenDependency] (with the same content)
     * corresponds to different graph entries after serialization/deserialization happened.
     */
    @Test
    fun `dependencies equal by content stay distinct after round trip`() {
        val restored = dependencyNode().toGraph().roundTrip().root
        val root = RootDependencyNodeStub(children = [restored, dependencyNode(), dependencyNode()])

        val dependencies = root.toGraph().roundTrip().root.children
            .map { (it as MavenDependencyNode).dependency }

        assertEquals(3, dependencies.toSet().size, "Dependencies should be distinct objects")
    }

    private fun constraintNode() = MavenDependencyConstraintNodeWithContext(
        Context {},
        MavenDependencyConstraintImpl("group", "module", Version(requires = "1.0.0")),
    )

    private fun dependencyNode() = MavenDependencyNodeWithContext(
        Context {},
        MavenCoordinates("group", "module", "1.0.0"),
        false,
    )

    private fun DependencyGraph.roundTrip(): DependencyGraph = GraphJson.json.decodeFromString(
        DependencyGraph.serializer(),
        GraphJson.json.encodeToString(DependencyGraph.serializer(), this),
    )
}
