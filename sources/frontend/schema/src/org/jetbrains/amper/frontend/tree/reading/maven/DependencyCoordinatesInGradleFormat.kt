/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.userGuideUrl
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.annotations.Nls

class DependencyCoordinatesInGradleFormat(
    source: FileBuildProblemSource,
    override val coordinates: String,
    @field:UsedInIdePlugin
    val gradleScope: GradleScope,
    @field:UsedInIdePlugin
    val trimmedCoordinates: String,
) : MavenCoordinatesParsingProblem(source) {

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.CoordinatesInGradleFormat
    override val message: @Nls String = SchemaBundle.message(
        "dependency.coordinates.in.gradle.format",
        coordinates,
        "$userGuideUrl/dependencies/#transitivity-and-scope",
    )
}