/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.LinkTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.web.WebCompileKlibTask
import org.jetbrains.amper.tasks.web.WebLinkTask
import org.jetbrains.amper.util.BuildType

internal fun ProjectTasksBuilder.setupWasmTasks(
    platform: Platform,
    createCompileTask: (
        module: AmperModule,
        platform: Platform,
        userCacheRoot: AmperUserCacheRoot,
        jdkProvider: JdkProvider,
        taskOutputRoot: TaskOutputRoot,
        incrementalCache: IncrementalCache,
        taskName: TaskName,
        tempRoot: AmperProjectTempRoot,
        isTest: Boolean,
        processRunner: ProcessRunner,
        terminal: Terminal,
    ) -> WebCompileKlibTask,
    createLinkTask: (
        module: AmperModule,
        platform: Platform,
        userCacheRoot: AmperUserCacheRoot,
        jdkProvider: JdkProvider,
        buildOutputRoot: AmperBuildOutputRoot,
        incrementalCache: IncrementalCache,
        taskName: TaskName,
        tempRoot: AmperProjectTempRoot,
        isTest: Boolean,
        compileKLibTaskId: TaskId,
        processRunner: ProcessRunner,
        buildType: BuildType,
    ) -> WebLinkTask,
) {
    allModules()
        .alsoPlatforms(platform)
        .alsoTests()
        .withEach {
            val compileKLibTaskName = CommonTaskType.Compile.getTaskName(module, platform, isTest)
            tasks.registerTask(
                task = createCompileTask(
                    module,
                    platform,
                    context.userCacheRoot,
                    context.jdkProvider,
                    context.getTaskOutputPath(compileKLibTaskName),
                    context.incrementalCache,
                    compileKLibTaskName,
                    context.projectTempRoot,
                    isTest,
                    context.processRunner,
                    context.terminal,
                ),
                dependsOn = buildList {
                    add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                    if (isTest) {
                        // todo (AB) : Check if this is required for test KLib compilation
                        add(CommonTaskType.Compile.getTaskName(module, platform, isTest = false))
                    }
                },
            )
        }

    allModules()
        .alsoPlatforms(platform)
        .alsoTests()
        .alsoBuildTypes()
        .withEach {
            if (needsLinkedExecutable(module, isTest)) {
                val compileKLibTaskName = CommonTaskType.Compile.getTaskName(module, platform, isTest)

                val linkAppTaskName = LinkTaskType.getTaskName(module, platform, isTest, buildType)
                tasks.registerTask(
                    task = createLinkTask(
                        module,
                        platform,
                        context.userCacheRoot,
                        context.jdkProvider,
                        context.buildOutputRoot,
                        context.incrementalCache,
                        linkAppTaskName,
                        context.projectTempRoot,
                        isTest,
                        compileKLibTaskName.id,
                        context.processRunner,
                        buildType,
                    ),
                    dependsOn = buildList {
                        add(compileKLibTaskName)
                        add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                        if (isTest) {
                            add(CommonTaskType.Compile.getTaskName(module, platform, isTest = false))
                        }
                    }
                )
            }
        }

    // KLIB compilation must only see the exported ('api') part of the transitive module dependencies
    // so that non-exported types don't leak into the consumer's compilation classpath.
    allModules()
        .alsoPlatforms(platform)
        .alsoTests()
        .selectModuleDependencies(ResolutionScope.COMPILE).withEach {
            tasks.registerDependency(
                CommonTaskType.Compile.getTaskName(module, platform, isTest),
                CommonTaskType.Compile.getTaskName(dependsOn, platform, false)
            )
        }

    // Linking, on the other hand, needs the whole runtime closure of KLIBs.
    allModules()
        .alsoPlatforms(platform)
        .alsoTests()
        .alsoBuildTypes()
        .filter { needsLinkedExecutable(it.module, it.isTest) }
        .selectModuleDependencies(ResolutionScope.RUNTIME).withEach {
            tasks.registerDependency(
                LinkTaskType.getTaskName(module, platform, isTest, buildType),
                CommonTaskType.Compile.getTaskName(dependsOn, platform, false)
            )
        }
}

internal fun needsLinkedExecutable(module: AmperModule, isTest: Boolean) =
    module.type.isApplication() || isTest