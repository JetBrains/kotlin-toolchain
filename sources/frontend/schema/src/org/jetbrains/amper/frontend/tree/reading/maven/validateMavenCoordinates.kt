/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading.maven

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls
import java.nio.file.InvalidPathException
import kotlin.io.path.Path
import kotlin.text.split

context(reporter: ProblemReporter)
internal fun validateAndReportMavenCoordinates(
    origin: PsiElement,
    coordinates: String,
): Boolean {
    if (' ' in coordinates) {
        reporter.reportMessage(MavenCoordinatesHaveSpace(PsiBuildProblemSource(origin), coordinates))
        return false
    }

    if ('\n' in coordinates || '\r' in coordinates) {
        reporter.reportMessage(MavenCoordinatesHaveLineBreak(PsiBuildProblemSource(origin), coordinates))
        return false
    }

    if ('/' in coordinates || '\\' in coordinates) {
        reporter.reportMessage(MavenCoordinatesHaveSlash(PsiBuildProblemSource(origin), coordinates))
        return false
    }

    val coordsAndPackaging = coordinates.trim().split("@")
    val parts = coordsAndPackaging.first().split(":")
    val packagingType = coordsAndPackaging.getOrNull(1)

    if (parts.size < 2) {
        reporter.reportMessage(MavenCoordinatesHaveTooFewParts(PsiBuildProblemSource(origin), coordinates, parts.size))
        reportIfCoordinatesAreGradleLike(origin, coordinates)
        return false
    }

    if (parts.size > 4) {
        reporter.reportMessage(MavenCoordinatesHaveTooManyParts(PsiBuildProblemSource(origin), coordinates, parts.size))
        return false
    }

    (parts + listOfNotNull(packagingType)).forEach { part ->
        try {
            // It throws InvalidPathException in case coordinates contain some restricted symbols.
            @Suppress("RETURN_VALUE_NOT_USED_COERCION")
            Path(part)
        } catch (e: InvalidPathException) {
            reporter.reportMessage(
                MavenCoordinatesShouldBuildValidPath(PsiBuildProblemSource(origin), coordinates, part, e.toString())
            )
            reportIfCoordinatesAreGradleLike(origin, coordinates)
            return false
        }

        if (part.endsWith(".")) {
            reporter.reportMessage(MavenCoordinatesHavePartEndingWithDot(PsiBuildProblemSource(origin), coordinates))
            return false
        }
    }

    return true
}

context(reporter: ProblemReporter)
private fun reportIfCoordinatesAreGradleLike(
    origin: PsiElement,
    coordinates: String,
) {
    val probableGradleScope = GradleScope.parseGradleScope(coordinates)
    if (probableGradleScope != null) {
        val [gradleScope, trimmedCoordinates] = probableGradleScope
        reporter.reportMessage(
            DependencyCoordinatesInGradleFormat(
                source = PsiBuildProblemSource(origin),
                coordinates = coordinates,
                gradleScope = gradleScope,
                trimmedCoordinates = trimmedCoordinates
            )
        )
    }
}

abstract class MavenCoordinatesParsingProblem(
    override val source: FileBuildProblemSource,
    override val level: Level = Level.Error,
) : BuildProblem {
    override val type: BuildProblemType = BuildProblemType.Generic

    abstract val coordinates: String

    open val details: @Nls String? get() = null

    open val shortMessage: @Nls String get() = message

    val detailedMessage: @Nls String
        get() = if (details == null) message else "$message\n$details"
}