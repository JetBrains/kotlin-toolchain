/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls

@Suppress("DEPRECATION")
class UnresolvedModuleDeclaration(
    val modulePath: TraceableString,
) : PsiBuildProblem(Level.Error, BuildProblemType.UnresolvedReference) {

    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement
        get() = modulePath.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.UnresolvedModuleDeclaration
    override val message: @Nls String
        get() = SchemaBundle.message(
            messageKey = "project.module.path.0.unresolved",
            modulePath.value,
        )
}