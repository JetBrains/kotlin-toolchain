/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.custom

import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.events.payload.TaskMonikerSpec
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.plugins.TaskFromPluginDescription
import org.jetbrains.amper.frontend.plugins.generated.ShadowCompilationArtifactKind
import org.jetbrains.amper.frontend.plugins.generated.ShadowResolutionScope
import org.jetbrains.amper.frontend.plugins.generated.ShadowSourcesKind
import org.jetbrains.amper.stdlib.collections.joinToString
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.getModuleDependencies
import org.jetbrains.amper.tasks.getTaskName

private fun TaskFromPluginDescription.taskName() = TaskName(
    id = backendTaskId,
    spec = TaskMonikerSpec.FromPlugin(
        moduleName = enabledIn.userReadableName,
        pluginId = pluginId.value,
        name = name,
    ),
)

fun ProjectTasksBuilder.setupTasksFromPlugins() {
    if (!includePluginTasks) {
        return
    }

    allModules().withEach {
        module.tasksFromPlugins.forEach { taskDescription ->
            val taskDependencies = mutableListOf<TaskName>()
            for (sourcesRequest in taskDescription.requestedModuleSources) {
                val fragments = sourcesRequest.from.fragmentsTargeting(Platform.JVM, isTest = false)
                if (sourcesRequest.node.includeGenerated) {
                    val taskName = TaskName(
                        module = module,
                        internalName = taskDescription.backendTaskId.value + "*resolve-${sourcesRequest.propertyLocation}",
                        operationMoniker =
                            "assembling module sources for `${sourcesRequest.propertyLocation.joinToString(".")}`",
                    )
                    tasks.registerTask(
                        ModuleSourcesResolveTask(
                            taskName = taskName,
                            fragmentsForSources = fragments,
                            request = sourcesRequest,
                        )
                    )
                    taskDependencies.add(taskName)
                } else {
                    sourcesRequest.node.sourceDirectories =
                        sourcesRequest.from.fragmentsTargeting(Platform.JVM, isTest = false).flatMap {
                            when (sourcesRequest.node.kind) {
                                ShadowSourcesKind.KotlinJavaSources -> it.sourceRoots
                                ShadowSourcesKind.Resources -> listOf(it.resourcesPath)
                            }
                        }
                }
            }
            for (request in taskDescription.requestedCompilationArtifacts) {
                val taskType = when (request.node.kind) {
                    ShadowCompilationArtifactKind.Jar -> CommonTaskType.Jar
                    ShadowCompilationArtifactKind.Classes -> CommonTaskType.MergedClasses
                }
                taskDependencies += taskType.getTaskName(request.from, Platform.JVM)
            }
            taskDependencies += taskDescription.requestedClasspaths.map { classpathRequest ->
                val resolutionScope = when(classpathRequest.node.scope) {
                    ShadowResolutionScope.Runtime -> ResolutionScope.RUNTIME
                    ShadowResolutionScope.Compile -> ResolutionScope.COMPILE
                }
                val taskName = TaskName(
                    module = module,
                    internalName = taskDescription.backendTaskId.value + "*resolve-${classpathRequest.propertyLocation}",
                    operationMoniker =
                        "assembling classpath for `${classpathRequest.propertyLocation.joinToString(".")}`",
                )
                val task = ResolveClasspathRequestTask(
                    taskName = taskName,
                    classpathRequest = classpathRequest,
                )
                tasks.registerTask(
                    task,
                    dependsOn = buildList {
                        if (resolutionScope != ResolutionScope.COMPILE) {
                            classpathRequest.localDependencies.forEach { module ->
                                add(CommonTaskType.Jar.getTaskName(module, Platform.JVM))
                                module.getModuleDependencies(
                                    isTest = false,
                                    platform = Platform.JVM,
                                    dependencyReason = resolutionScope,
                                    userCacheRoot = context.userCacheRoot,
                                    incrementalCache = context.incrementalCache,
                                ).forEach {
                                    add(CommonTaskType.Jar.getTaskName(it, Platform.JVM))
                                }
                            }
                        }

                        val resolveExternalTaskName = TaskName(
                            module = module,
                            internalName = taskName.id.value + "*external",
                            operationMoniker = "resolving external dependencies for the " +
                                    "`${classpathRequest.propertyLocation.joinToString(".")}`",
                        )
                        tasks.registerTask(ResolveCustomExternalDependenciesTask(
                            taskName = resolveExternalTaskName,
                            module = module,
                            incrementalCache = context.incrementalCache,
                            userCacheRoot = context.userCacheRoot,
                            resolutionScope = resolutionScope,
                            localDependencies = classpathRequest.localDependencies,
                            externalDependencies = classpathRequest.externalDependencies,
                        ))
                        add(resolveExternalTaskName)
                    }
                )
                taskName
            }
            val task = TaskFromPlugin(
                taskName = taskDescription.taskName(),
                module = module,
                description = taskDescription,
                buildOutputRoot = context.buildOutputRoot,
                terminal = context.terminal,
                incrementalCache = context.incrementalCache,
            )
            tasks.registerTask(
                task, dependsOn = buildList {
                    addAll(taskDependencies)
                    add(CommonTaskType.RuntimeClasspath.getTaskName(taskDescription.codeSource, Platform.JVM, isTest = false))
                    addAll(taskDescription.dependsOn.map { it.taskName() })
                }
            )
        }
    }
}
