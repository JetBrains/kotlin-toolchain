/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls

@Deprecated("Classifiers are now supported in coordinates since AMPER-774. " +
        "The reporting for incorrect classifier matching shorthand (missing space problem) will be moved to after-DR phase.")
@UsedInIdePlugin
class MavenClassifiersAreNotSupported(
    source: FileBuildProblemSource,
    override val coordinates: String,
    val classifier: String,
) : MavenCoordinatesParsingProblem(source, level = Level.Warning) {

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.MavenClassifiersAreNotSupported
    override val message: @Nls String = SchemaBundle.message("maven.classifiers.are.not.supported", coordinates, classifier)

    @UsedInIdePlugin
    val classifierCanBeShorthand: Boolean = when (classifier) {
        "compile-only",
        "runtime-only",
        "exported",
            -> true

        else -> false
    }

    override val details: @Nls String = buildString {
        append(SchemaBundle.message("maven.classifiers.are.not.supported.details", coordinates, classifier))
        if (classifierCanBeShorthand) {
            append(" ")
            append(SchemaBundle.message("maven.classifiers.are.not.supported.details.shorthand", classifier))
        }
    }
}