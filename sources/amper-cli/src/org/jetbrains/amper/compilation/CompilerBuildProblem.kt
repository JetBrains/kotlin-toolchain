/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import kotlinx.serialization.Serializable
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileWithLineColumnProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.LineAndColumn
import org.jetbrains.amper.problems.reporting.LineAndColumnRange
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.jetbrains.annotations.Nls
import kotlin.io.path.readText

object CompilerBuildProblemId : DiagnosticId

@Serializable
sealed interface CompilerBuildProblem : BuildProblem {
    val moduleName: String

    override val diagnosticId: DiagnosticId
        get() = CompilerBuildProblemId
    override val type: BuildProblemType
        get() = BuildProblemType.Generic
}

@NonIdealDiagnostic
@Serializable
data class GlobalCompilerBuildProblem(
    override val moduleName: String,
    override val message: @Nls String,
    override val level: Level,
) : CompilerBuildProblem {
    override val source: BuildProblemSource = GlobalBuildProblemSource

}

@Serializable
data class FileCompilerBuildProblem(
    override val moduleName: String,
    override val message: @Nls String,
    override val level: Level,
    override val source: CompilerBuildProblemSource,
): CompilerBuildProblem

/**
 * A location in source code associated with a compiler message
 * (mirrors [org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer.SourceLocation]).
 *
 * @property file the file path
 * @property line the 1-based start line number
 * @property column the 1-based start column number
 * @property lineEnd the 1-based end line number, or `-1` if not applicable
 * @property columnEnd the 1-based end column number, or `-1` if not applicable
 */
@Serializable
data class CompilerBuildProblemSource(
    override val file: SerializablePath,
    val line: Int,
    val column: Int,
    val lineEnd: Int,
    val columnEnd: Int,
) : FileWithLineColumnProblemSource {
    override val lineColumnRange: LineAndColumnRange
        get() = LineAndColumnRange(LineAndColumn(line, column), LineAndColumn(lineEnd, columnEnd))

    override val offsetRange: IntRange
        get() = run {
            val text = file.readText().lines()
            var startOffset = 0
            var currentLine = 1
            while (currentLine < line) {
                startOffset += text[currentLine - 1].length + 1
                currentLine++
            }
            startOffset += (column - 1)
            var endOffset = startOffset
            while (currentLine < lineEnd) {
                endOffset += text[currentLine - 1].length + 1
                currentLine++
            }
            endOffset += (columnEnd - 1)

            startOffset..endOffset
        }
}
