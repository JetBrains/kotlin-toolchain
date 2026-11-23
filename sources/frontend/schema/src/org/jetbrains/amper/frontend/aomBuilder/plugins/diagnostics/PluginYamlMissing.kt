/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls
import java.nio.file.Path

class PluginYamlMissing(
    override val source: FileBuildProblemSource,
    @field:UsedInIdePlugin
    val expectedPluginYamlPath: Path,
) : BuildProblem {
    override val level: Level = Level.Warning
    override val type: BuildProblemType = BuildProblemType.Generic
    override val diagnosticId: DiagnosticId = PluginDiagnosticId.PluginYamlMissing
    override val message: @Nls String = SchemaBundle.message("plugin.missing.plugin.yaml")
}