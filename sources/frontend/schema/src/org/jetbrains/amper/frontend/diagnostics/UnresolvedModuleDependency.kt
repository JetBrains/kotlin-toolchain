/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.schema.InternalDependency
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

@Suppress("DEPRECATION")
class UnresolvedModuleDependency(
    val dependency: InternalDependency,
    val projectRoot: Path,
    val possibleCorrectPath: Path?,
) : PsiBuildProblem(Level.Error, BuildProblemType.UnresolvedReference) {

    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement
        get() = dependency.pathDelegate.extractPsiElement()

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
