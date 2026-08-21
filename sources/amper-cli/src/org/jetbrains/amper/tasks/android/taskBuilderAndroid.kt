/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import com.android.prefs.AndroidLocationsSingleton
import com.android.sdklib.SystemImageTags.GOOGLE_APIS_TAG
import com.android.sdklib.devices.Abi
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.TaskGraphBuilder
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
import java.nio.file.Path

fun ProjectTasksBuilder.setupAndroidTasks() {
    val androidSdkPath = context.androidHomeRoot.path
    val needDefaultSystemImage = runSettings.deviceId == null

    allModules().alsoPlatforms(Platform.ANDROID).withEach {
        tasks.registerTask(
            task = CheckAndroidSdkLicenseTask(
                androidSdkPath = androidSdkPath,
                userCacheRoot = context.userCacheRoot,
                incrementalCache = context.incrementalCache,
                taskName = AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID),
            ),
            dependsOn = AndroidTaskType.InstallCmdlineTools.getTaskName(module, Platform.ANDROID)
        )
        tasks.setupAndroidCommandlineTools(module, androidSdkPath, context.userCacheRoot)
    }

    allModules().alsoPlatforms(Platform.ANDROID)
        .alsoTests()
        .withEach {
            tasks.setupAndroidPlatformTask(module, androidSdkPath, context.userCacheRoot, isTest)
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        .alsoTests()
        .withEach {
            tasks.setupDownloadBuildToolsTask(module, androidSdkPath, context.userCacheRoot, isTest)
            tasks.setupDownloadPlatformToolsTask(module, androidSdkPath, context.userCacheRoot, isTest)
            if (needDefaultSystemImage) {
                tasks.setupDownloadSystemImageTask(module, androidSdkPath, context.userCacheRoot, isTest)
            }
            tasks.registerTask(
                task = GetAndroidPlatformFileFromPackageTask(
                    packageName = "emulator",
                    androidSdkPath = androidSdkPath,
                    userCacheRoot = context.userCacheRoot,
                    taskName = AndroidTaskType.InstallEmulator.getTaskName(module, Platform.ANDROID, isTest)
                ),
                dependsOn = AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID)
            )
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        // No `alsoTests()` here - Android doesn't support unitTest-specific android res.
        .alsoBuildTypes()
        .withEach {
            val fragments = module.fragments.filter { it.isTest == isTest && it.platforms.contains(platform) }

            val prepareTaskName = AndroidTaskType.Prepare.getTaskName(
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
                    AndroidTaskType.InstallBuildTools.getTaskName(module, platform, isTest),
                    AndroidTaskType.InstallPlatformTools.getTaskName(module, platform, isTest),
                    AndroidTaskType.InstallPlatform.getTaskName(module, platform, isTest),
                    CommonTaskType.Dependencies.getTaskName(module, platform, isTest),
                )
            )
        }

    allModules().alsoPlatforms(Platform.ANDROID)
        .filterModuleType { it != ProductType.KMP_LIB }
        // no `alsoTests()` here - we do the unit testing ourselves, no need to build anything with Gradle for that.
        .alsoBuildTypes()
        .withEach {
            val taskName = AndroidTaskType.Build.getTaskName(
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
                val mockablePlatformJarTaskName = AndroidTaskType.MockablePlatformJar.getTaskName(module, platform, false)
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
                    add(AndroidTaskType.InstallPlatform.getTaskName(module, platform, isTest))
                    add(CommonTaskType.TransformDependencies.getTaskName(module, platform, isTest))
                    add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest))
                    if (module.type != ProductType.KMP_LIB && !isTest) {
                        add(AndroidTaskType.Prepare.getTaskName(module, platform, isTest = false, buildType))
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

                val aarTaskName = AndroidTaskType.Aar.getTaskName(module, platform, isTest = false, buildType)
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
                        add(AndroidTaskType.MockablePlatformJar.getTaskName(module, platform, false))
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
                            AndroidTaskType.Aar.getTaskName(dependsOn, platform, isTest = false, buildType)
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
                        add(AndroidTaskType.Aar.getTaskName(module, platform, isTest = false, buildType))
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
                        add(AndroidTaskType.InstallSystemImage.getTaskName(module, platform, false))
                    }
                    add(AndroidTaskType.InstallEmulator.getTaskName(module, platform, false))
                    add(AndroidTaskType.Build.getTaskName(module, platform, false, buildType))
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
            val taskName = AndroidTaskType.Bundle.getTaskName(module, Platform.ANDROID, false)
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
    androidSdkPath: Path,
    userCacheRoot: AmperUserCacheRoot,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val compileSdk = androidFragment?.settings?.android?.compileSdk ?: return
    registerTask(
        task = GetAndroidPlatformJarTask(
            getAndroidPlatformFileFromPackageTask = GetAndroidPlatformFileFromPackageTask(
                packageName = androidPlatformPackageName(
                    apiLevel = compileSdk.apiLevel.versionNumber,
                    minorApiLevel = compileSdk.minorApiLevel,
                    sdkExtension = compileSdk.sdkExtension,
                ),
                androidSdkPath = androidSdkPath,
                userCacheRoot = userCacheRoot,
                taskName = AndroidTaskType.InstallPlatform.getTaskName(module, Platform.ANDROID, isTest)
            )
        ),
        dependsOn = AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID)
    )
}

internal fun androidPlatformPackageName(
    apiLevel: Int,
    minorApiLevel: Int?,
    sdkExtension: Int?,
): String = buildString {
    append("platforms;android-")
    append(apiLevel)
    if (apiLevel >= 37 || minorApiLevel != 0) {
        // Minor API level equal to 0 started being appended to platform only since API level 37
        // - versions 1..35 don't have minor API levels at all
        // - 36 has 36 and 36.1
        // - 37 has 37.0 and 37.1
        // Future is unclear but, hopefully, Google uses the same versioning schema since 37 now.
        minorApiLevel?.let { append(".$it") }
    }
    sdkExtension?.let { append("-ext$it") }
}

private fun TaskGraphBuilder.setupDownloadBuildToolsTask(
    module: AmperModule,
    androidSdkPath: Path,
    userCacheRoot: AmperUserCacheRoot,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val buildToolsVersion = androidFragment?.settings?.android?.buildToolsVersion ?: return
    registerTask(
        task = GetAndroidPlatformFileFromPackageTask(
            packageName = "build-tools;$buildToolsVersion",
            androidSdkPath = androidSdkPath,
            userCacheRoot = userCacheRoot,
            taskName = AndroidTaskType.InstallBuildTools.getTaskName(module, Platform.ANDROID, isTest)
        ),
        dependsOn = AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID)
    )
}

private fun TaskGraphBuilder.setupDownloadPlatformToolsTask(
    module: AmperModule,
    androidSdkPath: Path,
    userCacheRoot: AmperUserCacheRoot,
    isTest: Boolean,
) {
    registerTask(
        GetAndroidPlatformFileFromPackageTask(
            "platform-tools",
            androidSdkPath,
            userCacheRoot,
            AndroidTaskType.InstallPlatformTools.getTaskName(module, Platform.ANDROID, isTest)
        ),
        AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID)
    )
}

private fun TaskGraphBuilder.setupDownloadSystemImageTask(
    module: AmperModule,
    androidSdkPath: Path,
    userCacheRoot: AmperUserCacheRoot,
    isTest: Boolean,
) {
    val androidFragment = getAndroidFragment(module, isTest)
    val compileSdk = androidFragment?.settings?.android?.compileSdk ?: return
    val abi = if (Arch.current == Arch.X64) Abi.X86_64 else Abi.ARM64_V8A
    val versionTag = buildString {
        append(compileSdk.apiLevel)
        if (compileSdk.apiLevel.versionNumber >= 37 || compileSdk.minorApiLevel != 0) {
            // Minor API level equal to 0 started being appended to platform only since API level 37
            // - versions 1..35 don't have minor API levels at all
            // - 36 has 36 and 36.1
            // - 37 has 37.0 and 37.1
            // Future is unclear but, hopefully, Google uses the same versioning schema since 37 now.
            append(".")
            append(compileSdk.minorApiLevel)
        }
    }
    registerTask(
        GetAndroidPlatformFileFromPackageTask(
            "system-images;android-$versionTag;${GOOGLE_APIS_TAG.id};$abi",
            androidSdkPath,
            userCacheRoot,
            AndroidTaskType.InstallSystemImage.getTaskName(module, Platform.ANDROID, isTest)
        ),
        AndroidTaskType.CheckAndroidSdkLicense.getTaskName(module, Platform.ANDROID)
    )
}

private fun TaskGraphBuilder.setupAndroidCommandlineTools(
    module: AmperModule,
    androidSdkPath: Path,
    userCacheRoot: AmperUserCacheRoot
) {
    registerTask(
        GetAndroidPlatformFileFromPackageTask(
            "cmdline-tools;22.0", // TODO: Temporary keep cmdline tools to 22.0, as 23.0 has issue in our tests on Windows. This code is to be replaced soon anyway.
            androidSdkPath = androidSdkPath,
            userCacheRoot = userCacheRoot,
            AndroidTaskType.InstallCmdlineTools.getTaskName(module, Platform.ANDROID)
        )
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

internal enum class AndroidTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    InstallBuildTools("installBuildTools", "installing `build-tools` for Android"),
    InstallPlatformTools("installPlatformTools", "installing `platform-tools` for Android"),
    InstallPlatform("installPlatform", "installing Android Platform"),
    InstallSystemImage("installSystemImage", "installing Android System Image"),
    InstallEmulator("installEmulator", "installing Android Emulator"),
    InstallCmdlineTools("installCmdlineTools", "installing `cmdline-tools` for Android"),
    CheckAndroidSdkLicense("checkAndroidSdkLicense", "checking Android SDK license"),
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
