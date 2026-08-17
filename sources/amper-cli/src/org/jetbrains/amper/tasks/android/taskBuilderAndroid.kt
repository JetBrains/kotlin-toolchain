/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import com.android.prefs.AndroidLocationsSingleton
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkPackageRequest
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.TaskGraphBuilder
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.ModuleSequenceCtx
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskNameFactory
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.tasks.getModuleDependencies
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.jvm.JvmClassesJarTask
import org.jetbrains.amper.tasks.jvm.JvmCompileTask
import org.jetbrains.amper.tasks.jvm.JvmRuntimeClasspathTask
import org.jetbrains.amper.tasks.jvm.JvmTestTask
import org.jetbrains.amper.util.BuildType

fun ProjectTasksBuilder.setupAndroidTasks() {
    val androidSdkPath = context.androidHomeRoot.path
    val androidSdkProvider = context.androidSdkProvider
    val needDefaultSystemImage = runSettings.deviceId == null

    val hasAndroidModules = allModules().alsoPlatforms(Platform.ANDROID).any()
    if (hasAndroidModules) {
        tasks.registerTask(
            GetAndroidPlatformFileFromPackageTask(
                AndroidSdkPackageRequest.CommandLineTools("latest"),
                androidSdkProvider = androidSdkProvider,
                taskName = AndroidGlobalTaskType.InstallCmdlineTools,
            )
        )
        tasks.registerTask(
            task = GetAndroidPlatformFileFromPackageTask(
                packageRequest = AndroidSdkPackageRequest.Emulator,
                androidSdkProvider = androidSdkProvider,
                taskName = AndroidGlobalTaskType.InstallEmulator
            ),
        )
        tasks.registerTask(
            GetAndroidPlatformFileFromPackageTask(
                packageRequest = AndroidSdkPackageRequest.PlatformTools,
                androidSdkProvider = androidSdkProvider,
                taskName = AndroidGlobalTaskType.InstallPlatformTools
            ),
        )
    }

    allModules().alsoPlatforms(Platform.ANDROID)
        .alsoTests()
        .withEach {
            tasks.setupAndroidPlatformTask(module, androidSdkProvider, isTest)
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        .alsoTests()
        .withEach {
            tasks.setupDownloadBuildToolsTask(module, androidSdkProvider, isTest)
            if (needDefaultSystemImage) {
                tasks.setupDownloadSystemImageTask(module, androidSdkProvider, isTest)
            }
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        // No `alsoTests()` here - Android doesn't support unitTest-specific android res.
        .alsoBuildTypes()
        .withEach {
            val fragments = module.fragments.filter { it.isTest == isTest && it.platforms.contains(platform) }

            val prepareTaskName = AndroidModuleTaskType.Prepare.getTaskName(
                module, platform, isTest, buildType,
            )
            tasks.registerTask(
                task = AndroidPrepareTask(
                    taskName = prepareTaskName,
                    module = module,
                    buildType = buildType,
                    incrementalCache = context.incrementalCache,
                    androidSdkPath = androidSdkPath,
                    fragments = fragments,
                    projectRoot = context.projectRoot,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputRoot = context.getTaskOutputPath(prepareTaskName),
                    buildLogsRoot = context.currentLogsRoot,
                    jdkProvider = context.jdkProvider,
                ),
                dependsOn = listOf(
                    AndroidModuleTaskType.InstallBuildTools.getTaskName(module, platform, isTest),
                    AndroidGlobalTaskType.InstallCmdlineTools,
                    AndroidGlobalTaskType.InstallPlatformTools,
                    AndroidModuleTaskType.InstallPlatform.getTaskName(module, platform, isTest),
                    CommonTaskType.Dependencies.getTaskName(module, platform, isTest),
                )
            )
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        // no `alsoTests()` here - we do the unit testing ourselves, no need to build anything with Gradle for that.
        .alsoBuildTypes()
        .withEach {
            val taskName = AndroidModuleTaskType.Build.getTaskName(
                module, platform, isTest, buildType,
            )
            tasks.registerTask(
                task = AndroidBuildTask(
                    module = module,
                    buildType = buildType,
                    isTest = false,
                    incrementalCache = context.incrementalCache,
                    androidSdkPath = androidSdkPath,
                    fragments = module.fragments.filter { !isTest && it.platforms.contains(platform) },
                    projectRoot = context.projectRoot,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputPath = context.getTaskOutputPath(taskName),
                    buildLogsRoot = context.currentLogsRoot,
                    jdkProvider = context.jdkProvider,
                    taskName = taskName,
                ),
                dependsOn = listOf(
                    CommonTaskType.RuntimeClasspath.getTaskName(module, platform, isTest, buildType),
                )
            )
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .alsoTests()
        .withEach {
            val fragments = module.fragments.filter { it.isTest == isTest && it.platforms.contains(platform) }

            tasks.registerTask(
                task = TransformAarExternalDependenciesTask(
                    taskName = CommonTaskType.TransformDependencies.getTaskName(module, Platform.ANDROID, isTest),
                    incrementalCache = context.incrementalCache,
                ),
                dependsOn = CommonTaskType.Dependencies.getTaskName(module, Platform.ANDROID, isTest),
            )

            if (isTest) {
                val mockablePlatformJarTaskName = AndroidModuleTaskType.MockablePlatformJar.getTaskName(module, platform, false)
                tasks.registerTask(
                    task = AndroidMockablePlatformJarTask(
                        taskName = mockablePlatformJarTaskName,
                        module = module,
                        buildType = buildType,
                        incrementalCache = context.incrementalCache,
                        androidSdkPath = androidSdkPath,
                        fragments = fragments,
                        projectRoot = context.projectRoot,
                        userCacheRoot = context.userCacheRoot,
                        taskOutputRoot = context.getTaskOutputPath(mockablePlatformJarTaskName),
                        buildLogsRoot = context.currentLogsRoot,
                        jdkProvider = context.jdkProvider,
                    ),
                    dependsOn = CommonTaskType.Dependencies.getTaskName(module, platform, false)
                )
            }
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .alsoTests()
        .alsoBuildTypes()
        .withEach {
            val fragments = module.fragments.filter { it.isTest == isTest && it.platforms.contains(platform) }

            // compile
            val compileTaskName = CommonTaskType.Compile.getTaskName(module, platform, isTest, buildType)

            tasks.registerTask(
                task = JvmCompileTask(
                    module = module,
                    isTest = isTest,
                    fragments = fragments,
                    userCacheRoot = context.userCacheRoot,
                    projectRoot = context.projectRoot,
                    tempRoot = context.projectTempRoot,
                    taskName = compileTaskName,
                    incrementalCache = context.incrementalCache,
                    buildOutputRoot = context.buildOutputRoot,
                    jdkProvider = context.jdkProvider,
                    buildType = buildType,
                    platform = platform,
                    processRunner = context.processRunner,
                    terminal = context.terminal,
                    openTelemetry = context.openTelemetry,
                ),
                dependsOn = buildList {
                    add(AndroidModuleTaskType.InstallPlatform.getTaskName(module, platform, isTest))
                    add(CommonTaskType.TransformDependencies.getTaskName(module, platform, isTest))
                    add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                    if (module.type != ProductType.KMP_LIB && !isTest) {
                        add(AndroidModuleTaskType.Prepare.getTaskName(module, platform, isTest = false, buildType))
                    }
                    if (isTest) {
                        // test compilation depends on main classes
                        add(CommonTaskType.Compile.getTaskName(module, platform, isTest = false, buildType))
                    }
                    module.getModuleDependencies(
                        isTest = isTest,
                        platform = platform,
                        dependencyReason = ResolutionScope.COMPILE,
                        userCacheRoot = context.userCacheRoot,
                        incrementalCache = context.incrementalCache,
                    ).forEach { dependsOn ->
                        val isAndroidDependency = platform in dependsOn.leafPlatforms
                        if (isAndroidDependency) {
                            add(CommonTaskType.Compile.getTaskName(dependsOn, platform, isTest = false, buildType))
                        } else {
                            // Fallback to depend on classes produced by the JVM fragment of the dependency
                            // to allow supporting Android -> JVM dependencies (see AMPER-5502).
                            checkDependencySupportsJvm(dependsOn)
                            add(CommonTaskType.Compile.getTaskName(dependsOn, Platform.JVM, isTest = false))
                        }
                    }
                }
            )

            if (!isTest) {
                // Production always deals with JAR -> AAR, even for `ProductType.ANDROID_APP`
                val jarTaskName = CommonTaskType.Jar.getTaskName(module, platform, isTest = false, buildType)
                tasks.registerTask(
                    task = JvmClassesJarTask(
                        taskName = jarTaskName,
                        module = module,
                        buildType = buildType,
                        taskOutputRoot = context.getTaskOutputPath(jarTaskName),
                        incrementalCache = context.incrementalCache,
                        platform = Platform.ANDROID,
                    ),
                    dependsOn = compileTaskName,
                )

                val aarTaskName = AndroidModuleTaskType.Aar.getTaskName(module, platform, isTest = false, buildType)
                tasks.registerTask(
                    task = AndroidAarTask(
                        taskName = aarTaskName,
                        incrementalCache = context.incrementalCache,
                        module = module,
                        buildType = buildType,
                        taskOutputRoot = context.getTaskOutputPath(aarTaskName),
                        tempRoot = context.projectTempRoot,
                    ),
                    dependsOn = buildList {
                        add(jarTaskName)
                        if (isComposeEnabledFor(module)) {
                            fragments.forEach { fragment ->
                                add(AndroidFragmentTaskType.PrepareComposeResources.getTaskName(fragment))
                            }
                        }
                    },
                )
            }

            val runtimeClasspathTaskName = CommonTaskType.RuntimeClasspath.getTaskName(module, platform, isTest, buildType)
            tasks.registerTask(
                task = JvmRuntimeClasspathTask(
                    module = module,
                    isTest = isTest,
                    taskName = runtimeClasspathTaskName,
                ),
                dependsOn = buildList {
                    if (isTest) {
                        add(CommonTaskType.TransformDependencies.getTaskName(module, platform, true))
                    }
                    // Third-party-dependencies
                    add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                    if (isTest) {
                        add(AndroidModuleTaskType.MockablePlatformJar.getTaskName(module, platform, false))
                    }

                    // Module-dependencies
                    module.getModuleDependencies(
                        isTest = isTest,
                        platform = platform,
                        dependencyReason = ResolutionScope.RUNTIME,
                        userCacheRoot = context.userCacheRoot,
                        incrementalCache = context.incrementalCache,
                    ).forEach { dependsOn ->
                        val isAndroidDependency = platform in dependsOn.leafPlatforms
                        if (!isAndroidDependency) checkDependencySupportsJvm(dependsOn)

                        val archiveTask = if (!isTest && isAndroidDependency) {
                            // Production always depends on AAR for Android module dependency
                            AndroidModuleTaskType.Aar.getTaskName(dependsOn, platform, isTest = false, buildType)
                        } else {
                            // Depend on JAR in case of test dependency or if we depend on a JVM module
                            val jarTaskPlatform = if (isAndroidDependency) platform else Platform.JVM
                            val jarBuildType = if (isAndroidDependency) buildType else null
                            CommonTaskType.Jar.getTaskName(dependsOn, jarTaskPlatform, isTest = false, jarBuildType)
                        }
                        add(archiveTask)
                    }

                    if (isTest) {
                        // Depend on the test classes
                        add(CommonTaskType.Compile.getTaskName(module, platform, isTest = true, buildType))
                        // Depend on the production JAR
                        add(CommonTaskType.Jar.getTaskName(module, platform, isTest = false, buildType))
                    } else {
                        // Production runtime classpath depends on AAR, not JAR
                        add(AndroidModuleTaskType.Aar.getTaskName(module, platform, isTest = false, buildType))
                    }
                }
            )
        }

    allModules().alsoPlatforms(Platform.ANDROID).withEach {
        if (isComposeEnabledFor(module)) {
            module.fragments.filter { Platform.ANDROID in it.platforms }.forEach { fragment ->
                tasks.registerTask(
                    task = AndroidComposeResourcesTask(
                        taskName = AndroidFragmentTaskType.PrepareComposeResources.getTaskName(fragment),
                        fragment = fragment,
                    ),
                )
            }
        }
    }

    allModules()
        .alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        .alsoBuildTypes()
        .withEach {
            // run
            val runTaskName = CommonTaskType.Run.getTaskName(module, platform, false, buildType)
            tasks.registerTask(
                task = AndroidRunTask(
                    taskName = runTaskName,
                    module = module,
                    buildType = buildType,
                    runSettings = runSettings,
                    androidSdkPath = androidSdkPath,
                    avdPath = AndroidLocationsSingleton.avdLocation,
                ),
                dependsOn = buildList {
                    if (needDefaultSystemImage) {
                        add(AndroidModuleTaskType.InstallSystemImage.getTaskName(module, platform, false))
                    }
                    add(AndroidGlobalTaskType.InstallEmulator)
                    add(AndroidModuleTaskType.Build.getTaskName(module, platform, false, buildType))
                }
            )
        }

    allModules()
        .alsoPlatforms(Platform.ANDROID)
        .alsoBuildTypes()
        .withEach {
            // test
            val testTaskName = CommonTaskType.Test.getTaskName(module, platform, isTest = false, buildType)
            tasks.registerTask(
                task = JvmTestTask(
                    module = module,
                    userCacheRoot = context.userCacheRoot,
                    tempRoot = context.projectTempRoot,
                    buildOutputRoot = context.buildOutputRoot,
                    taskName = testTaskName,
                    taskOutputRoot = context.getTaskOutputPath(testTaskName),
                    terminal = context.terminal,
                    runSettings = runSettings,
                    incrementalCache = context.incrementalCache,
                    jdkProvider = context.jdkProvider,
                    platform = Platform.ANDROID,
                    buildType = buildType,
                    processRunner = context.processRunner,
                ),
                dependsOn = listOf(
                    CommonTaskType.Compile.getTaskName(module, platform, true, buildType),
                    CommonTaskType.RuntimeClasspath.getTaskName(module, platform, true, buildType),
                ),
            )
        }

    allModules()
        .alsoPlatforms(Platform.ANDROID)
        .withEach {
            val fragments = module.fragments.filter { !it.isTest && it.platforms.contains(Platform.ANDROID) }
            val taskName = AndroidModuleTaskType.Bundle.getTaskName(module, Platform.ANDROID, false)
            tasks.registerTask(
                task = AndroidBundleTask(
                    module = module,
                    buildType = BuildType.Release,
                    incrementalCache = context.incrementalCache,
                    androidSdkPath = androidSdkPath,
                    fragments = fragments,
                    projectRoot = context.projectRoot,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputPath = context.getTaskOutputPath(taskName),
                    buildLogsRoot = context.currentLogsRoot,
                    jdkProvider = context.jdkProvider,
                    taskName = taskName,
                ),
                dependsOn = listOf(
                    CommonTaskType.RuntimeClasspath.getTaskName(module, Platform.ANDROID, false, BuildType.Release),
                )
            )
        }
}

private fun TaskGraphBuilder.setupAndroidPlatformTask(
    module: AmperModule,
    androidSdkProvider: AndroidSdkProvider,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val compileSdk = androidFragment?.settings?.android?.compileSdk ?: return
    registerTask(
        task = GetAndroidPlatformJarTask(
            getAndroidPlatformFileFromPackageTask = GetAndroidPlatformFileFromPackageTask(
                packageRequest = AndroidSdkPackageRequest.Platform(
                    compileSdk.apiLevel.versionNumber,
                    compileSdk.minorApiLevel,
                    compileSdk.sdkExtension
                ),
                androidSdkProvider = androidSdkProvider,
                taskName = AndroidModuleTaskType.InstallPlatform.getTaskName(module, Platform.ANDROID, isTest)
            )
        ),
    )
}

private fun TaskGraphBuilder.setupDownloadBuildToolsTask(
    module: AmperModule,
    androidSdkProvider: AndroidSdkProvider,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val buildToolsVersion = androidFragment?.settings?.android?.buildToolsVersion ?: return
    registerTask(
        task = GetAndroidPlatformFileFromPackageTask(
            packageRequest = AndroidSdkPackageRequest.BuildTools(buildToolsVersion),
            androidSdkProvider = androidSdkProvider,
            taskName = AndroidModuleTaskType.InstallBuildTools.getTaskName(module, Platform.ANDROID, isTest)
        ),
    )
}

private fun TaskGraphBuilder.setupDownloadSystemImageTask(
    module: AmperModule,
    androidSdkProvider: AndroidSdkProvider,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val versionNumber = androidFragment?.settings?.android?.targetSdk?.versionNumber ?: return
    val abi = if (Arch.current == Arch.X64) {
        AndroidSdkPackageRequest.SystemImage.ImageAbi.X86_64
    } else {
        AndroidSdkPackageRequest.SystemImage.ImageAbi.Arm64V8A
    }
    registerTask(
        GetAndroidPlatformFileFromPackageTask(
            AndroidSdkPackageRequest.SystemImage(
                apiLevel = versionNumber,
                tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
                abi = abi
            ),
            androidSdkProvider,
            AndroidModuleTaskType.InstallSystemImage.getTaskName(module, Platform.ANDROID, isTest)
        ),
    )
}

private fun getAndroidFragment(module: AmperModule, isTest: Boolean): LeafFragment? = module
    .fragments
    .filterIsInstance<LeafFragment>()
    .filter { it.isTest == isTest }.firstOrNull { Platform.ANDROID in it.platforms }

private fun ModuleSequenceCtx.checkDependencySupportsJvm(dependsOn: AmperModule) {
    check(Platform.JVM in dependsOn.leafPlatforms) {
        "Module ${dependsOn.userReadableName} has neither Android nor JVM as supported platforms " +
                "but mentioned as a dependency of ${module.userReadableName}. " +
                "Should've been forbidden on the frontend level."
    }
}


internal object AndroidGlobalTaskType {
    val InstallCmdlineTools = TaskName("installCmdlineTools", "installing `cmdline-tools` for Android")
    val InstallEmulator = TaskName("installEmulator", "installing Android Emulator")
    val InstallPlatformTools = TaskName("installPlatformTools", "installing `platform-tools` for Android")
}

internal enum class AndroidModuleTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    InstallBuildTools("installBuildTools", "installing `build-tools` for Android"),
    InstallPlatform("installPlatform", "installing Android Platform"),
    InstallSystemImage("installSystemImage", "installing Android System Image"),
    Aar("aar", "writing AAR"),
    Prepare("prepare", "preparing Android build"),
    Build("build", "building Android app"),
    Bundle("bundle", "bundling Android app"),
    MockablePlatformJar("mockablePlatformJar", "providing mockable platform JAR"),
}

private enum class AndroidFragmentTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.Fragment {
    PrepareComposeResources("prepareComposeResourcesForAndroid", "aggregating Android compose resources"),
}
