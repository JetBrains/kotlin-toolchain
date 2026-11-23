/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.schema.InternalDependency
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

class UnresolvedModuleDependency(
    val dependency: InternalDependency,
    val projectRoot: Path,
    val possibleCorrectPath: Path?,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.UnresolvedReference

    override val source: FileBuildProblemSource by lazy {
        PsiBuildProblemSource(dependency.pathDelegate.extractPsiElement())
    }

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.UnresolvedModuleDependency

    override val message: @Nls String
        get() {
            val relativePath = dependency.path.relativeTo(projectRoot)
            val relativePathString = relativePath.formatModulePath()

            return if (possibleCorrectPath == null) {
                SchemaBundle.message(
                    messageKey = "unresolved.module",
                    relativePathString
                )
            } else {
                SchemaBundle.message(
                    messageKey = "unresolved.module.with.hint",
                    relativePathString,
                    possibleCorrectPathString,
                )
            }
        }

    @UsedInIdePlugin
    val possibleCorrectPathString: String? = possibleCorrectPath?.formatModulePath()

    private fun Path.formatModulePath(): String {
        return "//" + normalize().invariantSeparatorsPathString
    }
}
