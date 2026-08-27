/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.nodejs.NodeJsProvider
import org.jetbrains.amper.pnpm.PnpmProvider
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.LinkTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskNameFactory
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.web.NpmInstallTask
import org.jetbrains.amper.tasks.web.WebTaskType

fun ProjectTasksBuilder.setupWasmJsTasks() {
    setupWasmTasks(
        Platform.WASM_JS,
        ::WasmJsCompileKlibTask,
        ::WasmJsLinkTask,
    )

    allModules()
        .alsoPlatforms(Platform.WASM_JS)
        .alsoTests()
        .withEach {
            val npmInstallTaskName = WebTaskType.NpmInstall.getTaskName(module, platform, isTest)
            tasks.registerTask(
                task = NpmInstallTask(
                    module = module,
                    platform = platform,
                    taskOutputPath = context.getTaskOutputPath(npmInstallTaskName),
                    taskName = npmInstallTaskName,
                    processRunner = context.processRunner,
                    incrementalCache = context.incrementalCache,
                    nodeJsProvider = NodeJsProvider(
                        context.userCacheRoot,
                        context.openTelemetry,
                    ),
                    pnpmProvider = PnpmProvider(
                        context.userCacheRoot,
                        context.openTelemetry,
                    ),
                ),
                dependsOn = [
                    CommonTaskType.Dependencies.getTaskName(module, platform, isTest),
                ],
            )
        }

    allModules()
        .alsoPlatforms(Platform.WASM_JS)
        .alsoBuildTypes()
        .filter { needsLinkedExecutable(it.module, isTest = false) }
        .withEach {
            val resolveDependenciesTaskName = CommonTaskType.Dependencies.getTaskName(module, platform, isTest)

            val linkAppTaskName = LinkTaskType.getTaskName(module, platform, isTest, buildType)

            val npmInstallTask = WebTaskType.NpmInstall.getTaskName(module, platform, isTest)

            val buildAppTaskName = WasmJsTaskType.BuildWasmJsApp.getTaskName(module, platform, isTest, buildType)
            tasks.registerTask(
                task = WasmJsBuildTask(
                    platform = platform,
                    module = module,
                    buildType = buildType,
                    taskOutputPath = context.getTaskOutputPath(buildAppTaskName),
                    taskName = buildAppTaskName,
                    tempRoot = context.projectTempRoot,
                    incrementalCache = context.incrementalCache,
                    userCacheRoot = context.userCacheRoot,
                ),
                dependsOn = [
                    linkAppTaskName,
                    resolveDependenciesTaskName,
                    npmInstallTask
                ]
            )

            val runTaskName = WasmJsTaskType.RunWasmJsApp.getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                task = BrowserRunTask(
                    taskName = runTaskName,
                    platform = platform,
                    buildType = buildType,
                    module = module,
                    runSettings = runSettings,
                ),
                dependsOn = [buildAppTaskName]
            )
        }

    allModules()
        .alsoPlatforms(Platform.WASM_JS)
        .alsoBuildTypes()
        .withEach {
            val resolveDependenciesTaskName =
                CommonTaskType.Dependencies.getTaskName(module, platform, isTest = true)

            val linkAppTaskName = LinkTaskType.getTaskName(module, platform, isTest = true, buildType)

            val npmInstallTask = WebTaskType.NpmInstall.getTaskName(module, platform, isTest = true)

            val buildAppTaskName = WasmJsTaskType.BuildWasmJsApp.getTaskName(module, platform, isTest = true, buildType)
            tasks.registerTask(
                task = WasmJsBuildTestTask(
                    platform = platform,
                    module = module,
                    buildType = buildType,
                    taskOutputPath = context.getTaskOutputPath(buildAppTaskName),
                    taskName = buildAppTaskName,
                    tempRoot = context.projectTempRoot,
                    incrementalCache = context.incrementalCache,
                    userCacheRoot = context.userCacheRoot,
                ),
                dependsOn = [
                    linkAppTaskName,
                    resolveDependenciesTaskName,
                    npmInstallTask
                ]
            )

            val testTaskName = CommonTaskType.Test.getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                task = BrowserTestTask(
                    taskName = testTaskName,
                    platform = platform,
                    buildType = buildType,
                    module = module,
                    processRunner = context.processRunner,
                    runSettings = runSettings,
                    nodeJsProvider = NodeJsProvider(
                        context.userCacheRoot,
                        context.openTelemetry,
                    ),
                    pnpmProvider = PnpmProvider(
                        context.userCacheRoot,
                        context.openTelemetry,
                    ),
                    taskOutputPath = context.getTaskOutputPath(testTaskName),
                ),
                dependsOn = [buildAppTaskName, npmInstallTask]
            )

        }
}

internal enum class WasmJsTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    BuildWasmJsApp("buildWasmJsApp", "building Wasm JS app"),
    RunWasmJsApp("runWasmJsApp", "running Wasm JS app"),
}