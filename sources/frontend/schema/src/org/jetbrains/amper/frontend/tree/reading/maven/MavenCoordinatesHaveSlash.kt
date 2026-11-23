/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.annotations.Nls

/**
 * Coordinates are used for building paths to the artifacts in the Amper local storage, slashes will affect the path.
 */
class MavenCoordinatesHaveSlash(
    source: FileBuildProblemSource,
    override val coordinates: String,
) : MavenCoordinatesParsingProblem(source) {

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.MavenCoordinatesHaveSlash
    override val message: @Nls String = SchemaBundle.message("maven.coordinates.have.slash")

    val slashIndices = coordinates.indices.filter { coordinates[it] == '\\' || coordinates[it] == '/' }

    override val details: @Nls String = buildString {
        appendLine(coordinates)
        var lastIndex = 0
        for (index in slashIndices) {
            append(" ".repeat(index - lastIndex))
            append("^")
            lastIndex = index + 1
        }
    }
}
