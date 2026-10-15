/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.distinctBfsSequence
import org.jetbrains.amper.frontend.Model
import kotlin.test.Test
import kotlin.test.assertTrue

class GraphConsistencyTest: BaseModuleDrTest() {

    @Test
    fun `check parents in a dependencies graph - ide`() = runSlowModuleDependenciesTest {
        checkParentsInDependenciesGraph(true)
    }

    @Test
    fun `check parents in a dependencies graph - classpath`() = runSlowModuleDependenciesTest {
        checkParentsInDependenciesGraph(false)
    }

    private suspend fun checkParentsInDependenciesGraph(ideSyncMode: Boolean) {
        val aom: Model = getTestProjectModel("jvm-transitive-dependencies", testDataRoot)

        val graph = with(ModuleDependencies) {
            if (ideSyncMode) {
                aom.resolveProjectDependencies(
                    defaultTestResolutionSettings,
                    ResolutionRunSettings(
                        incrementalCacheUsage = getIncrementalCacheUsage()
                    )
                )
            } else {
                resolveModuleDependencies(
                    aom.modules,
                    defaultTestResolutionSettings,
                    ResolutionRunSettings(
                        incrementalCacheUsage = getIncrementalCacheUsage()
                    ),
                    filter = ModuleResolutionFilter(ResolutionScope.RUNTIME, platforms = setOf(ResolutionPlatform.JVM)),
                )
            }
        }

        graph.root.distinctBfsSequence().forEach {
            val parents = it.parents
            assertTrue("Parents are empty for node ${it.key}") {
                parents.isNotEmpty()
                        || graph.root == it
                        || it is ModuleDependencyNode
            }

            it.parents.forEach { parent ->
                assertTrue("Node ${parent.key} is registered as parent of node ${it.key}, but doesn't contain it among its children") {
                    parent.children.contains(it)
                }
            }
        }
    }
}
