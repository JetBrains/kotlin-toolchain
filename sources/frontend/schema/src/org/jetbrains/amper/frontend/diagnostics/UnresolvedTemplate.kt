/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.relativeTo

class UnresolvedTemplate(
    val templatePath: TraceablePath,
    val moduleDirectory: Path,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.UnresolvedReference

    override val source: FileBuildProblemSource by lazy { PsiBuildProblemSource(templatePath.extractPsiElement()) }

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.UnresolvedTemplate
    override val message: @Nls String
        get() {
            val relativePath = templatePath.value.relativeTo(moduleDirectory)
            return SchemaBundle.message("unresolved.template", relativePath)
        }
}