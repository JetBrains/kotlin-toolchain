/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins

import org.jetbrains.amper.frontend.AmperPlugin
import org.jetbrains.amper.frontend.aomBuilder.ModuleBuildCtx
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.TaskGraphBuildContext
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.buildTaskGraph
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.diagnoseConflictingTasksOutputs
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.diagnoseOverlappingPaths
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.diagnoseTaskDependencyLoops
import org.jetbrains.amper.frontend.aomBuilder.plugins.taskGraph.wireTaskDependencies
import org.jetbrains.amper.frontend.plugins.TaskFromPluginDescription
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.mapConcurrentlyPreservingProblemOrder

/**
 * Read all `plugin.yaml` for plugins in the project. Apply parsed plugins to every module that has them enabled.
 *
 * @return all the project's amper plugins.
 */
context(_: ProblemReporter, _: SchemaTypingContext, projectContext: AmperProjectContext)
internal suspend fun buildAndApplyPlugins(
    pluginData: List<PluginData>,
    modules: List<ModuleBuildCtx>,
): List<AmperPlugin> {
    val pluginReaders = readPlugins(modules, pluginData)

    modules.mapConcurrentlyPreservingProblemOrder { moduleBuildCtx, moduleReporter ->
        context(moduleReporter) {
            applyPlugins(pluginReaders, moduleBuildCtx, modules)
        }
    }

    val allTasksDescriptions: List<TaskFromPluginDescription> = modules.flatMap { it.module.tasksFromPlugins }
    val context = TaskGraphBuildContext(
        buildDir = projectContext.projectBuildDir,
        projectRootDir = projectContext.projectRoot.path,
    )
    context(context) {
        for (task in allTasksDescriptions) {
            diagnoseOverlappingPaths(task)
        }

        diagnoseConflictingTasksOutputs(allTasksDescriptions)

        val taskGraph = buildTaskGraph(
            tasks = allTasksDescriptions,
            modules = modules.map { it.module },
        )

        diagnoseTaskDependencyLoops(taskGraph)
        wireTaskDependencies(taskGraph)
    }

    return pluginReaders
}
