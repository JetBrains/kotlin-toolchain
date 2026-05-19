/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.ModuleTasksPart
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RepositoriesModulePart
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.allSourceFragmentCompileDependencies
import org.jetbrains.amper.frontend.doCapitalize
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.frontend.mavenPublishRepositories
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.shouldPublishSourcesJars
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.js.JsTaskType
import org.jetbrains.amper.tasks.native.NativeTaskType
import org.jetbrains.amper.tasks.publication.MavenCentralPublishTask
import org.jetbrains.amper.tasks.publication.MavenPublishTask
import org.jetbrains.amper.tasks.publication.PrepareMavenCentralBundleTask
import org.jetbrains.amper.tasks.wasm.WasmTaskType
import org.jetbrains.amper.util.BuildType

internal enum class CommonTaskType(override val prefix: String) : PlatformTaskType {
    Compile("compile"),
    Ksp("ksp"),
    Dependencies("resolveDependencies"),
    TransformDependencies("transformDependencies"),
    Classes("classes"),
    MergedClasses("mergedClasses"),
    Jar("jar"),
    JavadocJar("javadocJar"),
    SourcesJar("sourcesJar"),
    Publish("publish"),
    Run("run"),
    RuntimeClasspath("runtimeClasspath"),
    KspProcessorDependencies("resolveKspProcessorDependencies"),
    KspProcessorClasspath("kspProcessorClasspath"),
    Test("test"),
}

internal enum class CommonFragmentTaskType(override val prefix: String) : FragmentTaskType {
    CompileMetadata("compileMetadata"),
}

fun ProjectTasksBuilder.setupCommonTasks() {
    val moduleDependenciesMap = with(ModuleDependencies) {
        val resolutionSettings = AmperResolutionSettings(
            context.userCacheRoot, context.incrementalCache, GlobalOpenTelemetry.get())
        model.moduleDependencies(resolutionSettings)
            .associateBy { it.module }
    }
    allModules()
        .alsoPlatforms()
        .alsoTests()
        .withEach {
            tasks.registerTask(
                ResolveExternalDependenciesTask(
                    module = module,
                    userCacheRoot = context.userCacheRoot,
                    incrementalCache = context.incrementalCache,
                    platform = platform,
                    isTest = isTest,
                    moduleDependencies = moduleDependenciesMap[module]!!,
                    taskName = CommonTaskType.Dependencies.getTaskName(module, platform, isTest),
                )
            )
        }

    allFragments().forEach {
        val taskName = CommonFragmentTaskType.CompileMetadata.getTaskName(it)
        tasks.registerTask(
            MetadataCompileTask(
                taskName = taskName,
                module = it.module,
                fragment = it,
                userCacheRoot = context.userCacheRoot,
                taskOutputRoot = context.getTaskOutputPath(taskName),
                incrementalCache = context.incrementalCache,
                tempRoot = context.projectTempRoot,
                jdkProvider = context.jdkProvider,
                processRunner = context.processRunner,
            )
        )
        // TODO make dependency resolution a module-wide task instead (when contexts support sets of platforms)
        it.platforms.forEach { leafPlatform ->
            tasks.registerDependency(
                taskName = taskName,
                dependsOn = CommonTaskType.Dependencies.getTaskName(it.module, leafPlatform)
            )
        }

        it.allSourceFragmentCompileDependencies.forEach { otherFragment ->
            tasks.registerDependency(
                taskName = taskName,
                dependsOn = CommonFragmentTaskType.CompileMetadata.getTaskName(otherFragment)
            )
        }
    }

    allModules()
        .alsoPlatforms()
        .withEach {
            val sourcesJarTaskName = CommonTaskType.SourcesJar.getTaskName(module, platform)
            tasks.registerTask(
                SourcesJarTask(
                    taskName = sourcesJarTaskName,
                    module = module,
                    platform = platform,
                    taskOutputRoot = context.getTaskOutputPath(sourcesJarTaskName),
                    incrementalCache = context.incrementalCache,
                )
            )
            val javadocJarTaskName = CommonTaskType.JavadocJar.getTaskName(module, platform)
            tasks.registerTask(
                JavadocJarTask(
                    taskName = javadocJarTaskName,
                    module = module,
                    platform = platform,
                    taskOutputRoot = context.getTaskOutputPath(javadocJarTaskName),
                    incrementalCache = context.incrementalCache,
                )
            )
        }

    allModules()
        .withEach {
            if (module.isPublishingEnabled()) {
                val prepareMavenPublishablesTaskName = TaskName.moduleTask(module, "prepareMavenPublishables")
                tasks.registerTask(
                    PrepareMavenPublishablesTask(
                        taskName = prepareMavenPublishablesTaskName,
                        module = module,
                        taskOutputRoot = context.getTaskOutputPath(prepareMavenPublishablesTaskName),
                        incrementalCache = context.incrementalCache,
                    ),
                    dependsOn = buildList {
                        module.leafPlatforms.forEach { platform ->
                            addAll(tasksWithPlatformSpecificPublishablesFor(platform))
                            if (module.shouldPublishSourcesJars()) {
                                add(CommonTaskType.SourcesJar.getTaskName(module, platform))
                            }
                            add(CommonTaskType.JavadocJar.getTaskName(module, platform))
                            // we need dependencies to get publication coordinate overrides (e.g. -jvm variant)
                            add(CommonTaskType.Dependencies.getTaskName(module, platform, isTest = false))
                        }
                    },
                )

                if (module.publishingSettings.mavenCentral.enabled) {
                    val packageTaskName = TaskName.moduleTask(module, "prepareMavenCentralBundle")
                    tasks.registerTask(
                        task = PrepareMavenCentralBundleTask(
                            taskName = packageTaskName,
                            module = module,
                            incrementalCache = context.incrementalCache,
                            taskOutputRoot = context.getTaskOutputPath(packageTaskName),
                        ),
                        dependsOn = listOf(prepareMavenPublishablesTaskName),
                    )
                    tasks.registerTask(
                        task = MavenCentralPublishTask(
                            taskName = TaskName.moduleTask(module, "publishToMavenCentral"),
                            module = module,
                        ),
                        dependsOn = listOf(packageTaskName),
                    )
                }

                val publishRepositories = module.mavenPublishRepositories
                for (repository in publishRepositories) {
                    val publishTaskName = publishTaskNameFor(module, repository)
                    tasks.registerTask(
                        task = MavenPublishTask(
                            taskName = publishTaskName,
                            module = module,
                            targetRepository = repository,
                        ),
                        dependsOn = listOf(prepareMavenPublishablesTaskName),
                    )

                    // Publish task should depend on publishing of modules which this module depends on
                    // TODO It could be optional in the future by, e.g., introducing an option to `publish` command
                    val localModuleDependencies = module.fragments.filter { !it.isTest }
                        .flatMap { it.externalDependencies }
                        .filterIsInstance<LocalModuleDependency>()
                        .map { it.module }
                        .distinctBy { it.userReadableName }
                    for (moduleDependency in localModuleDependencies) {
                        tasks.registerDependency(
                            taskName = publishTaskName,
                            dependsOn = publishTaskNameFor(moduleDependency, repository),
                        )
                    }
                }
            }
        }
}

// TODO We should probably use some task artifact type to represent publishables and avoid having to list them here
private fun ModuleSequenceCtx.tasksWithPlatformSpecificPublishablesFor(platform: Platform): List<TaskName> = buildList {
    when (platform) {
        Platform.JVM -> add(CommonTaskType.Jar.getTaskName(module, platform, isTest = false))
        Platform.ANDROID -> add(CommonTaskType.Jar.getTaskName(module, platform, isTest = false, BuildType.Release))
        Platform.JS -> add(JsTaskType.CompileKLib.getTaskName(module, platform, isTest = false))
        Platform.WASM_JS,
        Platform.WASM_WASI,
            -> add(WasmTaskType.CompileKLib.getTaskName(module, platform, isTest = false))
        Platform.LINUX_X64,
        Platform.LINUX_ARM64,
        Platform.TVOS_ARM64,
        Platform.TVOS_X64,
        Platform.TVOS_SIMULATOR_ARM64,
        Platform.MACOS_X64,
        Platform.MACOS_ARM64,
        Platform.IOS_ARM64,
        Platform.IOS_SIMULATOR_ARM64,
        Platform.IOS_X64,
        Platform.WATCHOS_ARM64,
        Platform.WATCHOS_ARM32,
        Platform.WATCHOS_DEVICE_ARM64,
        Platform.WATCHOS_SIMULATOR_ARM64,
        Platform.MINGW_X64,
        Platform.ANDROID_NATIVE_ARM32,
        Platform.ANDROID_NATIVE_ARM64,
        Platform.ANDROID_NATIVE_X64,
        Platform.ANDROID_NATIVE_X86,
            -> add(NativeTaskType.CompileKLib.getTaskName(module, platform, isTest = false, BuildType.Release))
        Platform.COMMON,
        Platform.WEB,
        Platform.NATIVE,
        Platform.LINUX,
        Platform.APPLE,
        Platform.TVOS,
        Platform.MACOS,
        Platform.IOS,
        Platform.WATCHOS,
        Platform.MINGW,
        Platform.ANDROID_NATIVE,
            -> error("Platform $platform is not a leaf, yet appeared in leafPlatforms")
    }
}

internal fun publishTaskNameFor(module: AmperModule, repository: RepositoriesModulePart.Repository): TaskName =
    TaskName.moduleTask(module, "publishTo${repository.id.doCapitalize()}")

// TODO: Still in use. Redesign/remove
fun ProjectTasksBuilder.setupCustomTaskDependencies() {
    allModules().withEach {
        val tasksSettings = module.parts.filterIsInstance<ModuleTasksPart>().singleOrNull() ?: return@withEach
        for ((taskName, taskSettings) in tasksSettings.settings) {
            val thisModuleTaskName = TaskName.moduleTask(module, taskName)

            for (dependsOnTaskName in taskSettings.dependsOn) {
                val dependsOnTask = if (dependsOnTaskName.startsWith(":")) {
                    TaskName(dependsOnTaskName)
                } else {
                    TaskName.moduleTask(module, dependsOnTaskName)
                }

                tasks.registerDependency(thisModuleTaskName, dependsOnTask)
            }
        }
    }
}