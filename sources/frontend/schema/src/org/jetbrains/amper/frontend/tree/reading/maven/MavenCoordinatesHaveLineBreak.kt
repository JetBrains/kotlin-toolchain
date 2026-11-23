/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.annotations.Nls

class MavenCoordinatesHaveLineBreak(
    source: FileBuildProblemSource,
    override val coordinates: String,
) : MavenCoordinatesParsingProblem(source) {

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.MavenCoordinatesHaveLineBreak
    override val message: @Nls String = SchemaBundle.message("maven.coordinates.have.line.break")

    val lineBreakIndices = coordinates.indices.filter { coordinates[it] == '\n' || coordinates[it] == '\r' }

    override val details: @Nls String = buildString {
        val sanitizedCoordinates = coordinates
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        appendLine(sanitizedCoordinates)
        var lastIndex = 0
        for (index in lineBreakIndices) {
            append(" ".repeat(index - lastIndex))
            append("^^")
            lastIndex = index + 1
        }
    }
}
