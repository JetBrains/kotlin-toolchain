/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.test.Dirs
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.fail

internal fun getTestProjectModel(testProjectName: String, testDataRoot: Path): Model {
    val projectPath = testDataRoot.resolve(testProjectName)
    return getTestProjectModel(projectPath)
}

internal fun copyTestProjectTo(testProjectName: String, testDataRoot: Path, testProjectRoot: Path) {
    val projectPath = testDataRoot.resolve(testProjectName)
    projectPath.toFile().copyRecursively(testProjectRoot.toFile(), overwrite = true)
}

internal fun getTestProjectModel(testProjectRoot: Path): Model {
    val aom = with(NoopProblemReporter) {
        val amperProjectContext = AmperProjectContext.create(rootDir = testProjectRoot, buildDir = null)
            ?: fail("Failed to create test project context")
        runBlocking {
            amperProjectContext.readProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList())
        }
    }
    return aom
}

internal val amperUserCacheRoot: AmperUserCacheRoot
    get() {
        val result = AmperUserCacheRoot(Dirs.userCacheRoot)
        assertIs<AmperUserCacheRoot>(result)
        return result
    }
