/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.frontend.tree.visitNodes
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Checks that every value with [org.jetbrains.amper.frontend.types.SchemaType.StringType.Semantics.TaskName]
 * is a valid task name.
 */
internal object InvalidTaskNameReferencesDiagnosticFactory : IsolatedPluginYamlDiagnosticsFactory {
    context(reporter: ProblemReporter)
    override fun analyze(pluginTree: RefinedMappingNode) {
        val tasks = pluginTree[PluginYamlRoot::tasks] as? MappingNode
        val taskNames = tasks?.children?.map { it.key }.orEmpty()

        val allTaskNameStrings = buildSet {
            pluginTree.visitNodes { node ->
                if (node is StringNode && node.semantics == SchemaType.StringType.Semantics.TaskName) {
                    add(node)
                }
            }
        }

        for (taskNameReference in allTaskNameStrings) {
            if (taskNameReference.value !in taskNames) {
                reporter.reportBundleError(
                    source = taskNameReference.asBuildProblemSource(),
                    diagnosticId = PluginDiagnosticId.InvalidCheckerTaskName,
                    messageKey = "plugin.invalid.task.name.reference",
                    taskNameReference.value,
                    taskNames.ifEmpty { ["<none>"] }.joinToString { "`$it`" },
                )
            }
        }
    }
}