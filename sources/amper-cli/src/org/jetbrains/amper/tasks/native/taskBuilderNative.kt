/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.LinkTaskType
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskNameFactory
import org.jetbrains.amper.tasks.getModuleDependencies
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.ios.IosTaskType
import org.jetbrains.amper.util.BuildType

private fun isIosApp(platform: Platform, module: AmperModule) =
    platform.isDescendantOf(Platform.IOS) && module.type.isApplication()

fun ProjectTasksBuilder.setupNativeTasks() {
    tasks.registerTask(
        task = CommonizeNativeDistributionTask(
            model = model,
            userCacheRoot = context.userCacheRoot,
            incrementalCache = context.incrementalCache,
            tempRoot = context.projectTempRoot,
            jdkProvider = context.jdkProvider,
            processRunner = context.processRunner,
        )
    )

    allModules().alsoPlatforms(Platform.NATIVE).withEach {
        val cinteropTaskName = NativeTaskType.Cinterop.getTaskName(module, platform)
        tasks.registerTask(
            task = NativeCInteropGenerateKlibTask(
                module = module,
                platform = platform,
                fragments = module.fragmentsTargeting(platform, false),
                buildOutputRoot = context.buildOutputRoot,
                userCacheRoot = context.userCacheRoot,
                incrementalCache = context.incrementalCache,
                taskName = cinteropTaskName,
                jdkProvider = context.jdkProvider,
                processRunner = context.processRunner,
                ignorePlatformFailures = cinteropGenSettings.ignorePlatformFailures,
            )
        )
    }

    allModules().withEach {
        if (!module.canHaveCinterop()) {
            return@withEach
        }

        val taskName = ModuleTaskTypes.CommonizeCinterop.getTaskName(module)
        tasks.registerTask(
            CommonizeCInteropKlibsTask(
                buildOutputRoot = context.buildOutputRoot,
                userCacheRoot = context.userCacheRoot,
                jdkProvider = context.jdkProvider,
                incrementalCache = context.incrementalCache,
                processRunner = context.processRunner,
                tempRoot = context.projectTempRoot,
                module = module,
                taskName = taskName,
            ),
            dependsOn = [CommonizeNativeDistributionTask.TASK_NAME],
        )
    }

    allModules()
        .alsoPlatforms(Platform.NATIVE)
        .alsoBuildTypes()
        .alsoTests()
        .withEach {
            val compileKLibTaskName = NativeTaskType.CompileKLib.getTaskName(module, platform, isTest, buildType)
            tasks.registerTask(
                task = NativeCompileKlibTask(
                    module = module,
                    platform = platform,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputRoot = context.getTaskOutputPath(compileKLibTaskName),
                    incrementalCache = context.incrementalCache,
                    taskName = compileKLibTaskName,
                    tempRoot = context.projectTempRoot,
                    isTest = isTest,
                    buildType = buildType,
                    jdkProvider = context.jdkProvider,
                    processRunner = context.processRunner,
                    terminal = context.terminal,
                ),
                dependsOn = buildList {
                    add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                    if (isTest) {
                        // todo (AB) : Check if this is required for test KLib compilation
                        add(NativeTaskType.CompileKLib.getTaskName(module, platform, isTest = false, buildType))
                    }
                },
            )
            if (needsLinkedExecutable(module, isTest)) {
                val [linkTaskName, compilationType] = getNativeLinkTaskDetails(platform, module, isTest, buildType)
                tasks.registerTask(
                    task = NativeLinkTask(
                        module = module,
                        platform = platform,
                        userCacheRoot = context.userCacheRoot,
                        taskOutputRoot = context.getTaskOutputPath(linkTaskName),
                        incrementalCache = context.incrementalCache,
                        taskName = linkTaskName,
                        tempRoot = context.projectTempRoot,
                        isTest = isTest,
                        buildType = buildType,
                        compilationType = compilationType,
                        compileKLibTaskId = compileKLibTaskName.id,
                        exportedKLibTaskIds = buildSet {
                            // Build the exported libraries set for iOS
                            if (compilationType == KotlinCompilationType.IOS_FRAMEWORK) {
                                module.getModuleDependencies(
                                    isTest = false,
                                    platform = platform,
                                    dependencyReason = ResolutionScope.COMPILE,
                                    userCacheRoot = context.userCacheRoot,
                                    incrementalCache = context.incrementalCache
                                ).forEach { dependsOn ->
                                    add(NativeTaskType.CompileKLib.getTaskName(dependsOn, platform, false, buildType).id)
                                }
                            }
                        },
                        jdkProvider = context.jdkProvider,
                        processRunner = context.processRunner,
                        xcodeBuildSettingsResolution = xcodeBuildSettingsResolution,
                    ),
                    dependsOn = buildList {
                        add(compileKLibTaskName)
                        add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                        if (isTest) {
                            add(NativeTaskType.CompileKLib.getTaskName(module, platform, isTest = false, buildType))
                        }
                        if (compilationType == KotlinCompilationType.IOS_FRAMEWORK) {
                            // Needed for bundleId inference
                            xcodeBuildSettingsResolution.taskDependency(module)?.let(::add)
                        }
                    }
                )
            }
        }

    allModules()
        .alsoPlatforms(Platform.NATIVE)
        .alsoTests()
        .alsoBuildTypes()
        .selectModuleDependencies(ResolutionScope.RUNTIME).withEach {
            tasks.registerDependency(
                NativeTaskType.CompileKLib.getTaskName(module, platform, isTest, buildType),
                NativeTaskType.CompileKLib.getTaskName(dependsOn, platform, false, buildType)
            )

            if (needsLinkedExecutable(module, isTest)) {
                tasks.registerDependency(
                    getNativeLinkTaskName(platform, module, isTest, buildType),
                    NativeTaskType.CompileKLib.getTaskName(dependsOn, platform, false, buildType)
                )
            }
        }

    allModules()
        .alsoPlatforms(Platform.NATIVE)
        .filter {
            // Skip running of ios/app modules, since it is handled in taskBuilderIos.kt
            it.module.type.isApplication() && !it.platform.isDescendantOf(Platform.IOS)
        }
        .alsoBuildTypes()
        .withEach {
            val runTaskName = CommonTaskType.Run.getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                NativeRunTask(
                    module = module,
                    taskName = runTaskName,
                    platform = platform,
                    buildType = buildType,
                    runSettings = runSettings,
                    terminal = context.terminal,
                    processRunner = context.processRunner,
                ),
                LinkTaskType.getTaskName(module, platform, isTest = false, buildType)
            )
        }

    allModules()
        .alsoPlatforms(Platform.NATIVE)
        .filterNot {
            // Skip testing of ios modules, since it is handled in taskBuilderIos.kt
            it.platform.isDescendantOf(Platform.IOS)
        }
        .alsoBuildTypes()
        .withEach {
            tasks.registerTask(
                NativeTestTask(
                    module = module,
                    taskName = CommonTaskType.Test.getTaskName(module, platform, buildType = buildType),
                    buildType = buildType,
                    platform = platform,
                    runSettings = runSettings,
                    terminal = context.terminal,
                    processRunner = context.processRunner,
                ),
                LinkTaskType.getTaskName(module, platform, isTest = true, buildType)
            )
        }
}

private fun needsLinkedExecutable(module: AmperModule, isTest: Boolean) =
    module.type.isApplication() || isTest

private fun getNativeLinkTaskName(platform: Platform, module: AmperModule, isTest: Boolean, buildType: BuildType) =
    getNativeLinkTaskDetails(platform, module, isTest, buildType).first

internal fun AmperModule.canHaveCinterop() = fragments.any { it.cinteropPath != null }

private fun getNativeLinkTaskDetails(
    platform: Platform,
    module: AmperModule,
    isTest: Boolean,
    buildType: BuildType,
) = when {
    isIosApp(platform, module) && !isTest ->
        IosTaskType.Framework.getTaskName(
            module,
            platform,
            false,
            buildType,
        ) to KotlinCompilationType.IOS_FRAMEWORK

    else ->
        LinkTaskType.getTaskName(module, platform, isTest, buildType) to KotlinCompilationType.BINARY
}

enum class NativeTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    CompileKLib("compile", "compiling"),
    Cinterop("cinterop", "processing cinterop definitions"),
}

enum class XcodebuildTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.XcodebuildPlatform {
    SwiftPMImport(
        "swiftPMImport",
        "Importing SwiftPM dependencies"
    ),
}