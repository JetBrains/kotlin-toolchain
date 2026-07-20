/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.ModuleTasksPart
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RepositoriesModulePart
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.frontend.mavenPublishRepositories
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.shouldPublishSourcesJars
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.android.AndroidTaskType
import org.jetbrains.amper.tasks.metadata.AssembleAllMetadataTask
import org.jetbrains.amper.tasks.metadata.allMetadataFragments
import org.jetbrains.amper.tasks.native.CommonizeNativeDistributionTask
import org.jetbrains.amper.tasks.native.NativeTaskType
import org.jetbrains.amper.tasks.publication.MavenCentralPublishTask
import org.jetbrains.amper.tasks.publication.MavenPublishTask
import org.jetbrains.amper.tasks.publication.PrepareMavenCentralBundleTask
import org.jetbrains.amper.util.BuildType

internal enum class CommonTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    Compile("compile", "compiling"),
    Ksp("ksp", "processing sources with KSP"),
    Dependencies("resolveDependencies", "resolving external dependencies"),
    TransformDependencies("transformDependencies", "transforming external dependencies"),
    Classes("classes", "aggregating compiled classes"),
    MergedClasses("mergedClasses", "merging compiled classes"),
    Jar("jar", "writing JAR"),
    JavadocJar("javadocJar", "writing Javadoc JAR"),
    SourcesJar("sourcesJar", "writing source JAR"),
    @Deprecated("kept for compat reasons, use `ModuleTaskTypes.Publish` instead")
    Publish("publish", "publishing"),
    Run("run", "running the app"),
    RuntimeClasspath("runtimeClasspath", "assembling runtime classpath"),
    KspProcessorDependencies("resolveKspProcessorDependencies", "resolving external KSP dependencies"),
    KspProcessorClasspath("kspProcessorClasspath", "assembling ksp processors classpath"),
    Test("test", "running unit tests"),
}

internal enum class CommonFragmentTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.Fragment {
    CompileMetadata("compileMetadata", "compiling Kotlin metadata"),
}

fun ProjectTasksBuilder.setupCommonTasks() {
    val moduleDependenciesMap = with(ModuleDependencies) {
        val resolutionSettings = AmperResolutionSettings(context.userCacheRoot, context.incrementalCache, GlobalOpenTelemetry.get())
        model.moduleDependencies(resolutionSettings).associateBy { it.module }
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

    allFragments()
        // Metadata compilation is done for multi-platform main fragments (with some exceptions).
        // Metadata compilation is skipped for { jvm + android } and { wasmjs + wasmwasi } modules.
        .allMetadataFragments()
        .forEach {
            val taskName = CommonFragmentTaskType.CompileMetadata.getTaskName(it)
            tasks.registerTask(
                MetadataCompileTask(
                    taskName = taskName,
                    module = it.module,
                    moduleDependencies = moduleDependenciesMap[it.module]!!,
                    fragment = it,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputRoot = context.getTaskOutputPath(taskName),
                    incrementalCache = context.incrementalCache,
                    tempRoot = context.projectTempRoot,
                    jdkProvider = context.jdkProvider,
                    processRunner = context.processRunner,
                ),
                dependsOn = buildList {
                    val allFragmentDependencies = with(ModuleDependencies) {
                        // todo (AB) : [AMPER-721] Check that native-only and mixed metadata-compilation
                        //  works with this compile dependencies classpath. Perhaps, all true native compile dependencies
                        //  should be used instead (including all transitive dependencies).
                        it.getSymbolsVisibilityFragmentsDependencies(moduleDependenciesMap[it.module]!!)
                    }
                    addAll(allFragmentDependencies.map{ CommonFragmentTaskType.CompileMetadata.getTaskName(it) })

                    add(CommonizeNativeDistributionTask.TASK_NAME)
                }
            )
        }

    allModules()
        // Any KMP module containing common main fragment is subject of the KMP publication
        .filter { !it.isTest && it.module.isMultiplatformPublication() }
        .withEach {
            val taskName = ModuleTaskTypes.AssembleAllMetadata.getTaskName(module)
            tasks.registerTask(
                AssembleAllMetadataTask(
                    taskName = taskName,
                    module = module,
                    moduleDependencies = moduleDependenciesMap[module]!!,
                    userCacheRoot = context.userCacheRoot,
                    tempRoot = context.projectTempRoot,
                    taskOutputRoot = context.getTaskOutputPath(taskName),
                    incrementalCache = context.incrementalCache,
                ),
                dependsOn = buildList {
                    addAll(
                        module.fragments
                            .asSequence()
                            .allMetadataFragments()
                            .map { CommonFragmentTaskType.CompileMetadata.getTaskName(it) }
                    )
                }
            )
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
                val prepareMavenPublishablesTaskName = ModuleTaskTypes.PrepareMavenPublishables.getTaskName(module)
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
                        if (module.isMultiplatformPublication()) {
                            add(ModuleTaskTypes.AssembleAllMetadata.getTaskName(module))
                        }
                    },
                )

                if (module.publishingSettings.mavenCentral.enabled) {
                    val packageTaskName = ModuleTaskTypes.PrepareMavenCentralBundle.getTaskName(module)
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
                            taskName = ModuleTaskTypes.Publish("mavenCentral").getTaskName(module),
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
                            incrementalCache = context.incrementalCache,
                            terminal = context.terminal,
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
        Platform.ANDROID -> add(AndroidTaskType.Aar.getTaskName(module, platform, isTest = false, BuildType.Release))
        Platform.JS,
        Platform.WASM_JS,
        Platform.WASM_WASI,
            -> add(CommonTaskType.Compile.getTaskName(module, platform, isTest = false))
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
    ModuleTaskTypes.Publish(repository.id).getTaskName(module)

// TODO: Still in use. Redesign/remove
fun ProjectTasksBuilder.setupCustomTaskDependencies() {
    allModules().withEach {
        val tasksSettings = module.parts.filterIsInstance<ModuleTasksPart>().singleOrNull() ?: return@withEach
        for ([taskName, taskSettings] in tasksSettings.settings) {
            val thisModuleTaskName = TaskId.moduleTask(module, taskName)

            for (dependsOnTaskName in taskSettings.dependsOn) {
                val dependsOnTask = if (dependsOnTaskName.startsWith(":")) {
                    TaskId(dependsOnTaskName)
                } else {
                    TaskId.moduleTask(module, dependsOnTaskName)
                }

                tasks.registerDependency(thisModuleTaskName, dependsOnTask)
            }
        }
    }
}