/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.doCapitalize
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildPlatform
import org.jetbrains.amper.util.BuildType

/**
 * A task type that serves as a [factory][getTaskName] for [TaskName]s.
 * It encodes a task's cardinality/scope, i.e., per-what entity is it registered.
 */
sealed interface TaskNameFactory {
    /**
     * A part of the internal [org.jetbrains.amper.frontend.TaskId].
     */
    val internalName: String

    /**
     * A user-visible task operation moniker to be displayed in the build progress widget as a "root" status message.
     */
    val operationMoniker: String

    /**
     * A task name factory for module-scoped task names.
     *
     * Use [getTaskName] to create a task name.
     */
    interface Module : TaskNameFactory

    /**
     * A task name factory for compilation/leaf-platform/leaf-fragment scoped task names.
     *
     * Use [getTaskName] to create a task name.
     */
    interface LeafPlatform : TaskNameFactory

    /**
     * A task name factory for fragment-scoped task names.
     *
     * Use [getTaskName] to create a task name.
     */
    interface Fragment : TaskNameFactory

    /**
     * A task name factory for xcodebuild destination scoped task names.
     *
     * Use [getTaskName] to create a task name.
     */
    interface XcodebuildPlatform : TaskNameFactory
}

/**
 * Constructs a module-scoped task name.
 */
fun TaskNameFactory.Module.getTaskName(
    module: AmperModule,
) = TaskName(module, internalName, operationMoniker)

/**
 * Constructs a compilation-scoped task name. Given parameters describe a compilation.
 */
fun TaskNameFactory.LeafPlatform.getTaskName(
    module: AmperModule,
    platform: Platform,
    isTest: Boolean = false,
    buildType: BuildType? = null,
    suffix: String = "",
) = TaskName(module, platform, isTest, buildType, suffix, internalName, operationMoniker)

/**
 * Constructs an xcodebuild platform-scoped task name
 */
fun TaskNameFactory.XcodebuildPlatform.getTaskName(
    module: AmperModule,
    platform: XcodebuildPlatform,
) = TaskName(module, platform, internalName, operationMoniker)

/**
 * Constructs a fragment-scoped task name.
 */
fun TaskNameFactory.Fragment.getTaskName(
    fragment: Fragment,
) = TaskName(fragment, internalName, operationMoniker)
