/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls
import java.nio.file.Path

@Suppress("DEPRECATION")
class PluginYamlMissing(
    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement,
    @field:UsedInIdePlugin
    val expectedPluginYamlPath: Path,
) : PsiBuildProblem(
    level = Level.Warning,
    type = BuildProblemType.Generic,
) {
    override val diagnosticId: DiagnosticId = PluginDiagnosticId.PluginYamlMissing
    override val message: @Nls String = SchemaBundle.message("plugin.missing.plugin.yaml")
}