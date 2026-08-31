/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.LinkTaskType
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskNameFactory
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.tasks.getTaskName

/**
 * Set up apple-related tasks.
 */
fun ProjectTasksBuilder.setupIosTasks() {
    allModules()
        .alsoPlatforms(Platform.IOS)
        .alsoBuildTypes()
        .filter {
            // Tests only make sense for simulator targets
            it.platform.isIosSimulator
        }
        .withEach {
            // TODO: compose resources for iOS tests?
            tasks.registerTask(
                task = IosKotlinTestTask(
                    taskName = CommonTaskType.Test.getTaskName(module, platform, isTest = false, buildType),
                    module = module,
                    runSettings = runSettings,
                    platform = platform,
                    buildType = buildType,
                    processRunner = context.processRunner,
                ),
                dependsOn = LinkTaskType.getTaskName(module, platform, isTest = true, buildType)
            )
        }

    allModules()
        .filterModuleType { it == ProductType.IOS_APP }
        .withEach {
            val manageXcodeProjectTaskName = ModuleTaskTypes.ManageXCodeProject.getTaskName(module)
            tasks.registerTask(
                task = ManageXCodeProjectTask(
                    taskName = manageXcodeProjectTaskName,
                    module = module,
                    terminal = context.terminal,
                ),
            )
            tasks.registerTask(
                task = PrepareIOSPlatformTask(
                    taskName = ModuleTaskTypes.PrepareIosPlatform.getTaskName(module),
                    terminal = context.terminal,
                    module = module,
                ),
                dependsOn = manageXcodeProjectTaskName,
            )
        }

    allModules()
        .alsoPlatforms(Platform.IOS)
        .filterModuleType { it == ProductType.IOS_APP }
        .filter { isComposeEnabledFor(it.module) }
        .withEach {
            val taskName = IosTaskType.PrepareComposeResources.getTaskName(module, platform)
            tasks.registerTask(
                task = IosComposeResourcesTask(
                    taskName = taskName,
                    leafFragment = module.leafFragments.single {
                        it.platform == platform && !it.isTest
                    },
                    incrementalCache = context.incrementalCache,
                    taskOutputRoot = context.getTaskOutputPath(taskName),
                    userCacheRoot = context.userCacheRoot,
                ),
                // to get the archives with KMP resources of the external dependencies
                dependsOn = [CommonTaskType.Dependencies.getTaskName(module, platform, isTest = false)],
            )
        }

    allModules()
        .alsoPlatforms(Platform.IOS)
        .alsoBuildTypes()
        .filterModuleType { it == ProductType.IOS_APP }
        .withEach {
            val preBuildTaskName = IosTaskType.PreBuildIosApp.getTaskName(module, platform, false, buildType)
            tasks.registerTask(
                task = IosPreBuildTask(
                    module = module,
                    taskName = preBuildTaskName,
                ),
                dependsOn = buildList {
                    if (isComposeEnabledFor(module)) {
                        add(IosTaskType.PrepareComposeResources.getTaskName(module, platform))
                    }
                    add(IosTaskType.Framework.getTaskName(module, platform, isTest = false, buildType))
                },
            )

            val buildTaskName = IosTaskType.BuildIosApp.getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                task = IosBuildTask(
                    platform = platform,
                    module = module,
                    buildType = buildType,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputPath = context.getTaskOutputPath(buildTaskName),
                    taskName = buildTaskName,
                    processRunner = context.processRunner,
                    terminal = context.terminal,
                    buildSettingsResolution = xcodeBuildSettingsResolution,
                ),
                dependsOn = [
                    preBuildTaskName,
                    // This goes here instead of pre-build because if the build is run from xcode, then managing the
                    // project won't help much anyway.
                    ModuleTaskTypes.ManageXCodeProject.getTaskName(module),
                    ModuleTaskTypes.PrepareIosPlatform.getTaskName(module),
                ],
            )

            val prepareDeviceForRunTaskName = IosTaskType.PrepareDeviceForRun
                .getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                task = IosPrepareDeviceForRunTask(
                    taskName = prepareDeviceForRunTaskName,
                    platform = platform,
                    processRunner = context.processRunner,
                    buildType = buildType,
                    buildSettingsResolution = xcodeBuildSettingsResolution,
                    runSettings = runSettings,
                ),
                dependsOn = listOfNotNull(
                    buildTaskName,
                    xcodeBuildSettingsResolution.taskDependency(module),
                    ModuleTaskTypes.PrepareIosPlatform.getTaskName(module),
                )
            )
            tasks.registerTask(
                task = IosRunTask(
                    taskName = IosTaskType.RunIosApp.getTaskName(module, platform, isTest = false, buildType),
                    platform = platform,
                    buildType = buildType,
                    module = module,
                    processRunner = context.processRunner,
                    terminal = context.terminal,
                ),
                dependsOn = [
                    prepareDeviceForRunTaskName,
                ]
            )
        }
}

internal enum class IosTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    Framework("framework", "linking iOS framework"),
    BuildIosApp("buildIosApp", "building iOS app"),
    RunIosApp("runIosApp", "running iOS app"),
    PrepareDeviceForRun("prepareIosDevice", "preparing to run iOS app"),
    PrepareComposeResources("prepareComposeResourcesForIos", "copying iOS compose resources"),
    PreBuildIosApp("preBuildIosApp", "preparing for xcodebuild")
}
