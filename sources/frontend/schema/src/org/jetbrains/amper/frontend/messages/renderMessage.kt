/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.catalogs.ComposeMaterial3UnknownVersionMappingProblem
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.ConflictingProperties
import org.jetbrains.amper.frontend.tree.EnumNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.IntNode
import org.jetbrains.amper.frontend.tree.ListNode
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.NullLiteralNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.StringInterpolationNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.enumConstantIfAvailable
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.stdlib.collections.forEachEndAware
import org.jetbrains.annotations.Nls

@OptIn(NonIdealDiagnostic::class)
fun renderMessage(problem: BuildProblem): @Nls String = when (problem) {
    is ConflictingProperties -> renderConflictingProperties(problem)
    is ComposeMaterial3UnknownVersionMappingProblem -> renderComposeMaterial3UnknownVersionMapping(problem)
    else -> buildString {
        fun appendSource(source: BuildProblemSource) {
            when (source) {
                is FileBuildProblemSource -> {
                    appendFileSource(source)
                    append(": ").append(problem.message)
                }
                is MultipleLocationsBuildProblemSource -> {
                    appendLine(problem.message)
                    appendLine("╰─ ${source.groupingMessage}")
                    appendMultipleSources(source.sources, indent = 3)
                }
                GlobalBuildProblemSource -> append(problem.message)
            }
        }

        appendSource(problem.source)
    }
}

private fun renderComposeMaterial3UnknownVersionMapping(problem: ComposeMaterial3UnknownVersionMappingProblem) = buildString {
    appendLine(problem.message)
    append(SchemaBundle.message("compose.material3.unknown.mapping.compose.version", problem.composeVersion))
    appendFileSource(PsiBuildProblemSource(problem.composeVersionTrace.extractPsiElement()))
}

private fun renderConflictingProperties(problem: ConflictingProperties): String = buildString {
    appendLine(SchemaBundle.message(
        "conflicting.properties.with.context",
        problem.keyValues.first().key,
        // TODO: Represent contexts in more user-friendly way than just toString?
        problem.contexts
    ))

    problem.keyValues.groupBy { it.value.renderValue() }
        .entries
        .forEachEndAware { isLast, [renderedValue, keyValues] ->
            appendLine(SchemaBundle.message("conflicting.properties.line", renderedValue))
            appendMultipleSources(keyValues.mapNotNull { it.value.trace.asBuildProblemSource() as? FileBuildProblemSource }, indent = 4)
            if (!isLast) appendLine()
        }
}

private fun TreeNode.renderValue(): String = when (this) {
    is ErrorNode -> "error"
    is NullLiteralNode -> "null"
    is ReferenceNode -> referencedPath.renderReference()
    is BooleanNode -> value.toString()
    is EnumNode -> enumConstantIfAvailable?.toString() ?: entryName
    is IntNode -> value.toString()
    is PathNode -> value.toString()
    is StringNode -> value
    is StringInterpolationNode -> parts.joinToString("") {
        when (it) {
            is StringInterpolationNode.Part.Text -> it.text.value
            is StringInterpolationNode.Part.Reference -> it.referencePath.renderReference()
        }
    }
    is MappingNode -> SchemaBundle.message("conflicting.properties.mapping.render")
    is ListNode -> SchemaBundle.message("conflicting.properties.list.render")
}

private fun List<TraceableString>.renderReference(): String =
    joinToString(separator = ".", prefix = $$"${", postfix = "}")

private fun StringBuilder.appendMultipleSources(sources: List<FileBuildProblemSource>, indent: Int = 0) {
    sources.forEachEndAware { isLast, source ->
        repeat(indent) { append(' ') }
        if (isLast) {
            append("╰─ ")
        } else {
            append("├─ ")
        }
        appendFileSource(source)
        if (!isLast) appendLine()
    }
}

private fun StringBuilder.appendFileSource(source: FileBuildProblemSource) {
    append(source.file.normalize())
    if (source is FileWithRangesBuildProblemSource) {
        val start = source.computeRange().start
        append(':').append(start.line).append(':').append(start.column)
    }
}