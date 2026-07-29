/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactTask
import org.jetbrains.amper.tasks.artifacts.api.Quantifier

class TaskGraphBuilder {
    // do not give access to the graph while it's being built
    private val taskRegistry = mutableMapOf<TaskId, Task>()
    private val dependencies = mutableMapOf<TaskId, Set<TaskId>>()
    private val builtinArtifacts = mutableListOf<Artifact>()

    fun registerTask(task: Task, dependsOn: List<TaskName> = emptyList()) =
        registerTaskImpl(task, dependsOn.map(TaskName::id))

    private fun registerTaskImpl(task: Task, dependsOn: List<TaskId> = emptyList()) {
        if (taskRegistry.contains(task.id)) {
            error("Task '${task.id}' already exists")
        }
        taskRegistry[task.id] = task

        for (dependsOnTaskName in dependsOn) {
            registerDependency(task.id, dependsOnTaskName)
        }
    }

    fun registerTask(task: Task, dependsOn: TaskName) = registerTaskImpl(task = task, dependsOn = listOf(dependsOn.id))

    fun registerDependency(taskName: TaskId, dependsOn: TaskId) {
        dependencies[taskName] = dependencies.getOrDefault(taskName, emptySet()) + dependsOn
    }

    fun registerDependency(taskName: TaskName, dependsOn: TaskName) {
        dependencies[taskName.id] = dependencies.getOrDefault(taskName.id, emptySet()) + dependsOn.id
    }

    /**
     * Registers a "builtin" artifact that is not built by any task but is expected to be already present
     * in the filesystem, e.g., a Kotlin source directory for the user-provided sources.
     */
    fun registerBuiltinArtifact(artifact: Artifact) {
        builtinArtifacts += artifact
    }

    fun build(): TaskGraph {
        autoWireTaskDependencies()
        return TaskGraph(nameToTask = taskRegistry.toMap(), dependencies = dependencies.toMap())
    }

    private fun autoWireTaskDependencies() {
        val artifactTasks = taskRegistry.values.filterIsInstance<ArtifactTask>()
        val builtBy = artifactTasks.flatMap { artifactTask ->
            artifactTask.produces.map { it to artifactTask }
        }

        builtBy.groupBy(
            keySelector = { [artifact, _] -> artifact.path.normalize() },
            valueTransform = { [_, task] -> task },
        ).forEach { [path, tasks] ->
            if (tasks.size > 1) {
                error("Artifact with $path is built by multiple tasks: ${tasks.map { it.taskName.id }}")
            }
        }

        artifactTasks.forEach { artifactTask ->
            val taskDependencies = mutableSetOf<ArtifactTask>()
            val resolvedConsumes = mutableMapOf<ArtifactSelector<*, *>, List<Artifact>>()

            artifactTask.consumes.forEach { query ->
                val matchedArtifacts = mutableListOf<Artifact>()
                for ([artifact, task] in builtBy) if (query.matches(artifact)) {
                    taskDependencies += task
                    matchedArtifacts += artifact
                }
                for (artifact in builtinArtifacts) if (query.matches(artifact)) {
                    matchedArtifacts += artifact
                }
                resolvedConsumes[query] = matchedArtifacts
            }
            resolvedConsumes.forEach { [selector, resolved] ->
                when(selector.quantifier) {
                    Quantifier.Single -> check(resolved.size == 1) { "Expected $selector, got ${resolved.size} artifacts" }
                    Quantifier.AtLeastOne -> check(resolved.isNotEmpty()) { "Expected $selector, got ${resolved.size} artifacts" }
                    Quantifier.AnyOrNone -> Unit // always okay
                }
            }
            artifactTask.injectConsumes(resolvedConsumes)
            taskDependencies.forEach {
                registerDependency(
                    taskName = artifactTask.taskName,
                    dependsOn = it.taskName,
                )
            }
        }
    }
}