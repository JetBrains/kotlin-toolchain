/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.annotations.Nls

class MavenCoordinatesHaveTooManyParts(
    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement,
    override val coordinates: String,
    @field:UsedInIdePlugin
    val partsSize: Int,
) : MavenCoordinatesParsingProblem() {

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.MavenCoordinatesHaveTooManyParts
    override val message: @Nls String = SchemaBundle.message("maven.coordinates.have.too.many.parts", coordinates, partsSize)
}