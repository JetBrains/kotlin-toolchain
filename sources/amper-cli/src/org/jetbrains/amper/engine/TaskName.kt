/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

import org.jetbrains.amper.events.payload.TaskMonikerSpec
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.doCapitalize
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.testSuffix
import org.jetbrains.amper.util.BuildType

/**
 * Represents a [Task] name;
 * Provides an internal [id] for the execution engine purposes;
 * and a user-visible [renderOperationMonikerWidget] as well.
 *
 * WARNING: This class doesn't provide data-like [Any.equals]/[Any.hashCode] implementation.
 *  Please use [id] for identity comparisons and in Sets/Maps.
 */
class TaskName(
    /**
     * Engine-level internal task id that identifies the task.
     */
    val id: TaskId,
    /**
     * A builder for creating a user-visible presentation of the root task operation in the status widget.
     *
     * TODO: Maybe use Bundle here somehow?
     */
    val spec: TaskMonikerSpec,
)

/**
 * Constructs a project-scoped (global) task name.
 *
 * @param internalName a part of the [TaskId]
 * @param operationMoniker user-readable operation moniker.
 */
fun TaskName(
    internalName: String,
    operationMoniker: String,
): TaskName {
    require(operationMoniker.isNotBlank()) { "blank `operationMoniker`" }
    return TaskName(
        id = TaskId(internalName),
        spec = TaskMonikerSpec.ProjectScoped(operationMoniker),
    )
}

/**
 * Constructs a module-scoped task name.
 *
 * NOTE: Prefer using a [org.jetbrains.amper.tasks.TaskNameFactory.Module] for non-adhoc tasks.
 *
 * @param internalName a part of the [TaskId]
 * @param operationMoniker user-readable operation moniker.
 */
fun TaskName(
    module: AmperModule,
    internalName: String,
    operationMoniker: String,
): TaskName {
    require(operationMoniker.isNotBlank()) { "blank `operationMoniker`" }
    return TaskName(
        id = TaskId.moduleTask(module, internalName),
        spec = TaskMonikerSpec.ModuleScoped(
            moduleName = module.userReadableName,
            operationMoniker = operationMoniker,
        ),
    )
}

/**
 * Constructs a compilation-scoped task name. Given parameters describe a compilation.
 *
 * NOTE: Prefer using a [org.jetbrains.amper.tasks.TaskNameFactory.LeafPlatform] for non-adhoc tasks.
 *
 * @param internalName a part of the [TaskId]
 * @param operationMoniker user-readable operation moniker.
 */
fun TaskName(
    module: AmperModule,
    platform: Platform,
    isTest: Boolean = false,
    buildType: BuildType? = null,
    suffix: String = "",
    internalName: String,
    operationMoniker: String,
): TaskName {
    require(operationMoniker.isNotBlank()) { "blank `operationMoniker`" }
    require(platform.isLeaf) { "$platform is not a leaf platform" }
    require(platform != Platform.JVM || buildType == null) { "BuildType must not be present in task names for JVM" }

    val uppercasePlatform = platform.pretty.replaceFirstChar { it.uppercase() }
    val buildTypeSuffix = buildType?.name ?: ""
    val testSuffix = isTest.testSuffix
    return TaskName(
        id = TaskId.moduleTask(module, "${internalName}$uppercasePlatform$testSuffix$buildTypeSuffix$suffix"),
        spec = TaskMonikerSpec.CompilationScoped(
            moduleName = module.userReadableName,
            platform = platform.pretty,
            isTest = isTest,
            buildType = buildType?.value,
            operationMoniker = operationMoniker,
        ),
    )
}

/**
 * Constructs a fragment-scoped task name.
 *
 * NOTE: Prefer using a [org.jetbrains.amper.tasks.TaskNameFactory.Fragment] for non-adhoc tasks.
 *
 * @param internalName a part of the [TaskId]
 * @param operationMoniker user-readable operation moniker.
 */
fun TaskName(
    fragment: Fragment,
    internalName: String,
    operationMoniker: String,
): TaskName {
    require(operationMoniker.isNotBlank()) { "blank `operationMoniker`" }
    return TaskName(
        id = TaskId.moduleTask(fragment.module, "$internalName${fragment.name.doCapitalize()}"),
        spec = TaskMonikerSpec.FragmentScoped(
            moduleName = fragment.module.userReadableName,
            fragmentName = fragment.name,
            operationMoniker = operationMoniker,
        ),
    )
}