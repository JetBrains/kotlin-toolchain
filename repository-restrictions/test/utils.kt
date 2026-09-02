/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.assertEqualsWithDiff

internal fun readAmperProjectModel(): Model = with(NoopProblemReporter) {
    val projectContext = AmperProjectContext.create(rootDir = Dirs.amperCheckoutRoot, buildDir = null)
        ?: error("Invalid project root: ${Dirs.amperCheckoutRoot}")
    projectContext.readProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList())
}

internal fun assertAlphabeticalOrder(items: List<String>, moniker: String) {
    assertEqualsWithDiff(
        expected = items.sorted(),
        actual = items,
        message = "$moniker are not in alphabetical order. Tip: you can select the lines and use the 'Sort lines' IDEA action",
    )
}
