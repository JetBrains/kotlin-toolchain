/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.MavenCoordinates
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.aomBuilder.ModuleBuildCtx
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.api.asTraceableValue
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.plugins.CheckFromPlugin
import org.jetbrains.amper.frontend.plugins.CustomCommandFromPlugin
import org.jetbrains.amper.frontend.plugins.FragmentDescriptor
import org.jetbrains.amper.frontend.plugins.GeneratedPathKind
import org.jetbrains.amper.frontend.plugins.GeneratedSourcesLanguage
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.plugins.TaskFromPluginDescription
import org.jetbrains.amper.frontend.plugins.generated.ShadowDependencyLocal
import org.jetbrains.amper.frontend.plugins.generated.ShadowDependencyMaven
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.collections.distinctBy
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Apply all enabled [plugins] to [moduleBuildCtx].
 * [allModules] is for the reference.
 */
context(problemReporter: ProblemReporter)
internal fun applyPlugins(
    plugins: List<AmperPluginImpl>,
    moduleBuildCtx: ModuleBuildCtx,
    allModules: List<ModuleBuildCtx>,
) {
    for (plugin in plugins) {
        val appliedPlugin: PluginYamlRoot = plugin.asAppliedTo(
            module = moduleBuildCtx,
        ) ?: continue

        val taskNameToPathCollector = appliedPlugin.tasks.entries.associate { [name, task] ->
            name to InputOutputCollector(task.action.backingTree)
        }

        // Collect generated marks from generatedSources/generatedResources blocks
        val topLevelMarkedOutputs = collectGeneratedMarks(
            appliedPlugin = appliedPlugin,
            allOutputPaths = taskNameToPathCollector.values.flatMap { it.allOutputPaths.map(TraceablePath::value) },
            moduleBuildCtx = moduleBuildCtx,
        )

        for ([name, task] in appliedPlugin.tasks) {
            val pathsCollector = taskNameToPathCollector.getValue(name)
            val outputsToMarks = pathsCollector.allOutputPaths.map { path: TraceablePath ->
                topLevelMarkedOutputs[path.value] ?: TaskFromPluginDescription.OutputPath(
                    path = path,
                    outputMark = null,
                )
            }
            val taskInfo = task.action.taskInfo
            val taskDescription = TaskFromPluginDescription(
                name = name,
                pluginId = plugin.id,
                enabledIn = moduleBuildCtx.module,
                backendTaskId = plugin.taskIdFor(moduleBuildCtx.module, name),
                actionFunctionJvmName = taskInfo.jvmFunctionName,
                actionClassJvmName = taskInfo.jvmFunctionClassName,
                actionArguments = task.action.backingTree,
                inputs = pathsCollector.allInputPaths.map { (path, inferTaskDependency) ->
                    TaskFromPluginDescription.InputPath(path, inferTaskDependency)
                },
                requestedModuleSources = pathsCollector.moduleSourcesNodes.mapNotNull { (node, propertyLocation) ->
                    val module = node.from.resolve(allModules) ?: return@mapNotNull null
                    TaskFromPluginDescription.ModuleSourcesRequest(
                        node = node,
                        from = module,
                        propertyLocation = propertyLocation,
                    )
                },
                requestedClasspaths = pathsCollector.classpathNodes.map { (node, propertyLocation) ->
                    val localModules = node.dependencies.filterIsInstance<ShadowDependencyLocal>()
                        .mapNotNull { it.resolve(allModules) }
                    TaskFromPluginDescription.ClasspathRequest(
                        node = node,
                        localDependencies = localModules.distinct(),
                        externalDependencies = node.dependencies.filterIsInstance<ShadowDependencyMaven>().map {
                            MavenCoordinates(
                                groupId = it.groupId,
                                artifactId = it.artifactId,
                                version = it.versionDelegate.asTraceableValue(),
                                classifier = it.classifier,
                                packagingType = it.packagingType,
                                trace = it.trace,
                            )
                        },
                        propertyLocation = propertyLocation,
                    )
                },
                requestedCompilationArtifacts = pathsCollector.compilationArtifactNodes.mapNotNull { node ->
                    val from = node.from.resolve(allModules) ?: return@mapNotNull null
                    TaskFromPluginDescription.CompilationResultRequest(
                        node = node,
                        from = from,
                    )
                },
                outputs = outputsToMarks,
                codeSource = plugin.pluginModule,
                explicitOptOutOfExecutionAvoidance = taskInfo.optOutOfExecutionAvoidance,
            )

            moduleBuildCtx.module.tasksFromPlugins += taskDescription
        }

        val taskNames = appliedPlugin.tasks.keys
        for (checker in appliedPlugin.checks) {
            if (checker.performedBy !in taskNames) {
                continue
            }
            val checkerDescription = CheckFromPlugin(
                name = checker.name,
                performedBy = plugin.taskIdFor(moduleBuildCtx.module, checker.performedBy),
                pluginId = plugin.id,
            )
            moduleBuildCtx.module.checksFromPlugins += checkerDescription
        }

        for (command in appliedPlugin.commands) {
            if (command.performedBy !in taskNames) {
                continue
            }
            val commandDescription = CustomCommandFromPlugin(
                name = command.name,
                performedBy = plugin.taskIdFor(moduleBuildCtx.module, command.performedBy),
                pluginId = plugin.id,
            )
            moduleBuildCtx.module.customCommandsFromPlugins += commandDescription
        }
    }
}

private fun selectFragmentByDescriptor(
    moduleBuildCtx: ModuleBuildCtx,
    descriptor: FragmentDescriptor,
): Fragment = moduleBuildCtx.module.fragments.first {
    // FIXME: `first` will crash on incorrect user input here;
    //  a diagnostic is needed
    it.isTest == descriptor.isTest && it.modifier == descriptor.modifier
}

/**
 * A generated output mark derived from [PluginYamlRoot.generated].
 */
private data class TopLevelOutputMark(
    val kind: GeneratedPathKind,
    val directoryPath: Path,
    val trace: Trace,
    val fragment: FragmentDescriptor,
)

/**
 * Collects [TopLevelOutputMark] entries from the [PluginYamlRoot.generated] top-level block.
 */
context(problemReporter: ProblemReporter)
private fun collectGeneratedMarks(
    moduleBuildCtx: ModuleBuildCtx,
    allOutputPaths: List<Path>,
    appliedPlugin: PluginYamlRoot,
): Map<Path, TaskFromPluginDescription.OutputPath> = buildList {
    val generated = appliedPlugin.generated
    for (sources in generated.sources) {
        this += TaskFromPluginDescription.OutputPath(
            path = sources.directoryDelegate.asTraceableValue(),
            outputMark = TaskFromPluginDescription.OutputMark(
                kind = when (sources.language) {
                    GeneratedSourcesLanguage.Kotlin -> GeneratedPathKind.KotlinSources
                    GeneratedSourcesLanguage.Java -> GeneratedPathKind.JavaSources
                },
                trace = sources.trace,
                associateWith = selectFragmentByDescriptor(moduleBuildCtx, sources.fragment),
            )
        )
    }

    for (resources in generated.resources) {
        this += TaskFromPluginDescription.OutputPath(
            path = resources.directoryDelegate.asTraceableValue(),
            outputMark = TaskFromPluginDescription.OutputMark(
                kind = GeneratedPathKind.JvmResources,
                trace = resources.trace,
                associateWith = selectFragmentByDescriptor(moduleBuildCtx, resources.fragment),
            )
        )
    }

    for (cinterop in generated.cinteropDefinitions) {
        this += TaskFromPluginDescription.OutputPath(
            path = cinterop.defFileDelegate.asTraceableValue(),
            outputMark = TaskFromPluginDescription.OutputMark(
                kind = GeneratedPathKind.CinteropDefFile,
                trace = cinterop.trace,
                associateWith = selectFragmentByDescriptor(moduleBuildCtx, cinterop.fragment),
            )
        )
    }

    // Diagnose those outputs that do not belong to any task
    forEach { output ->
        if (output.path.value !in allOutputPaths) {
            problemReporter.reportBundleError(
                source = appliedPlugin.asBuildProblemSource(),
                diagnosticId = PluginDiagnosticId.UndeclaredGeneratedDirectoryOutput,
                messageKey = "plugin.generated.directory.not.an.output",
                output.path.value.pathString,
            )
        }
    }
}.distinctBy(
    selector = { it.path },
    onDuplicates = { path, duplicateMarks ->
        val source = MultipleLocationsBuildProblemSource(
            sources = duplicateMarks.mapNotNull { it.path.asBuildProblemSource() as? FileBuildProblemSource },
            groupingMessage = SchemaBundle.message("plugin.generated.directory.duplicates.grouping"),
        )
        problemReporter.reportBundleError(
            source = source,
            diagnosticId = PluginDiagnosticId.ConflictingMarkedPluginPaths,
            messageKey = "plugin.generated.directory.duplicates",
            path,
        )
    }
).associateBy { it.path.value }

context(problemReporter: ProblemReporter)
private fun ShadowDependencyLocal.resolve(
    modules: List<ModuleBuildCtx>,
): AmperModule? {
    val module = modules.find { it.module.source.moduleDir == modulePath }
    if (module == null) {
        problemReporter.reportBundleError(
            // TODO: Relative path (as it was specified) would be better?
            //  blocker: that information is lost currently.
            source = asBuildProblemSource(),
            diagnosticId = FrontendDiagnosticId.UnresolvedModuleDependency,
            messageKey = "unresolved.module",
            modulePath.pathString,
        )
        return null
    }
    return module.module
}
