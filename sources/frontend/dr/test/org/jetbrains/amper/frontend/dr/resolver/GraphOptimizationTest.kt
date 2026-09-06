/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.assertSame

@Tag("gold-file")
class GraphOptimizationsTest : BaseModuleDrTest() {

    override val testGoldenFilesRoot: Path
        get() = super.testGoldenFilesRoot.resolve("optimizations")

    @Test
    fun `module related nodes' key are calculated once` (testInfo: TestInfo) =
        runModuleDependenciesTest {
            val aom = getTestProjectModel("jvm-empty", testDataRoot)

            val testFragmentDeps = doTestByFile(
                testInfo,
                aom,
                resolutionInput = ideSyncTestResolutionInput,
                module = "jvm-empty",
                filter = ideSyncModuleResolutionFilter,
            )

            checkNode(testFragmentDeps, ModuleDependencyNode::class)
            checkNode(testFragmentDeps, DirectFragmentDependencyNode::class)
        }

    private fun checkNode(testFragmentDeps: DependencyNode, kClass: KClass<*>) {
        val moduleNode = testFragmentDeps.distinctBfsSequence().first { kClass.isInstance(it) }
        moduleNode.checkKey(moduleNode.key.name)

        val serializedNode = moduleNode.toGraph().root
        serializedNode.checkKey(moduleNode.key.name)
    }

    private fun DependencyNode.checkKey(keyName: String) {
        Assertions.assertEquals(keyName, key.name)
        assertSame(key, key, "Key instance should not be recalculated on every access")
    }
}