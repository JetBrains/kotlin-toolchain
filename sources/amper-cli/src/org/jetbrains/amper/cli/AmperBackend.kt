/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.apprun.RunTarget
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.events.EventSinkContributors
import org.jetbrains.amper.cli.events.createGlobalSink
import org.jetbrains.amper.cli.options.UserJvmArgsOption
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.GenerateKlibsForIdeTask
import org.jetbrains.amper.engine.MaybeBuildTypeAware
import org.jetbrains.amper.engine.MaybePlatformAware
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.engine.TaskExecutor.TaskExecutionFailed
import org.jetbrains.amper.engine.TaskGraph
import org.jetbrains.amper.engine.TestTask
import org.jetbrains.amper.engine.id
import org.jetbrains.amper.engine.runTasksAndReportOnFailure
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.plugins.CustomCommandFromPlugin
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.stdlib.collections.filterIf
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.CinteropGenSettings
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.compose.GenerateResClassTask
import org.jetbrains.amper.tasks.compose.GenerateResourceAccessorsTask
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.tasks.composeHotReloadMode
import org.jetbrains.amper.tasks.getModuleDependencies
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.ios.IosPreBuildTask
import org.jetbrains.amper.tasks.ios.IosTaskType
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.tasks.jvm.JvmCompileTask
import org.jetbrains.amper.tasks.jvm.JvmHotRunTask
import org.jetbrains.amper.tasks.publication.MAVEN_CENTRAL_REPOSITORY_ID
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import org.jetbrains.amper.util.BuildType
import org.jetbrains.amper.util.currentHostRunnablePlatforms
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

class AmperBackend(
    val context: ProjectCliContext,
    /**
     * The Amper project model.
     */
    private val model: Model,
    /**
     * Settings that are passed from the command line to user-visible processes that Amper runs, such as tests or the
     * user's applications.
     */
    val runSettings: AllRunSettings,
    /**
     * Settings for the `cinterop` handling subsystem.
     */
    val cinteropGenSettings: CinteropGenSettings = CinteropGenSettings(),
    /**
     * Whether to include tasks that come from plugins to the task graph.
     * Affects both Amper and Maven-compat plugins.
     */
    val includePluginTasks: Boolean = true,
    /**
     * See [XcodeBuildSettingsResolution].
     */
    val xcodeBuildSettingsResolution: XcodeBuildSettingsResolution = XcodeBuildSettingsResolution,
    /**
     * Defines how other tasks are executed if a task fails.
     */
    val taskExecutionMode: TaskExecutor.Mode = TaskExecutor.Mode.FAIL_FAST,
    /**
     * [EventSinkContributors] that set up [org.jetbrains.amper.events.sink.EventSink]
     * hierarchical structure for the build.
     */
    val eventSinkContributors: EventSinkContributors,
) {
    internal val taskGraph: TaskGraph by lazy {
        spanBuilder("Build task graph").useWithoutCoroutines {
            ProjectTasksBuilder(
                context = context,
                model = model,
                runSettings = runSettings,
                cinteropGenSettings = cinteropGenSettings,
                includePluginTasks = includePluginTasks,
                xcodeBuildSettingsResolution = xcodeBuildSettingsResolution,
            ).build()
        }
    }

    private val taskExecutor: TaskExecutor by lazy {
        TaskExecutor(
            graph = taskGraph,
            mode = taskExecutionMode,
            problemReporter = context.problemReporter,
            globalEventSink = eventSinkContributors.createGlobalSink(),
            eventSinkContributors = eventSinkContributors,
        )
    }

    /**
     * Called by the 'build' command.
     * Builds ready-to-run and ready-to-test artifacts for all included modules/platforms/buildTypes.
     *
     * The idea is that `amper run` and `amper test`
     * (if called with compatible filters) will practically do no building work after `amper build`.
     *
     * If [platforms] is specified, only compilation/linking for those platforms should be run.
     *
     * If [modules] is specified, only compilation/linking for those modules should be run.
     *
     * If [buildTypes] are specified, only compilation/linking
     */
    suspend fun build(
        platforms: Set<Platform>? = null,
        modules: Set<String>? = null,
        buildTypes: Set<BuildType>? = null,
    ) {
        val possibleCompilationPlatforms = if (OsFamily.current.isMac) {
            Platform.leafPlatforms
        } else {
            // Apple targets could be compiled only on Mac OS X due to legal obstacles
            Platform.leafPlatforms.filter { !it.isDescendantOf(Platform.APPLE) }.toSet()
        }

        val platformsToCompile = platforms ?: possibleCompilationPlatforms
        val modulesToCompile = (modules?.map { model.getModuleByName(it) } ?: model.modules).toSet()

        val taskIds = taskGraph
            .tasks
            .filterIsInstance<BuildTask>()
            .filter {
                it.platform in platformsToCompile && it.module in modulesToCompile
            }
            .filterByBuildTypeAndReport(
                explicit = buildTypes,
                default = BuildType.Debug,
            )
            .map { it.id }
            .toSet()
        logger.debug("Selected tasks to compile: ${formatTaskNames(taskIds)}")

        if (taskIds.isEmpty()) {
            // TODO: Give more info on why there is nothing to build
            logger.warn("Nothing to build")
            return
        }
        taskExecutor.runTasksAndReportOnFailure(taskIds)
    }

    /**
     * Called by the 'package' command.
     * Packages artifacts for distribution for all included modules/platforms/buildTypes.
     *
     * If [platforms] is specified, only packaging for those platforms should be run.
     *
     * If [modules] is specified, only packaging for those modules should be run.
     *
     * If [buildTypes] are specified, only packaging for those build types should be run.
     *
     * If [formats] is specified, only packaging in those formats should be run.
     */
    suspend fun `package`(
        platforms: Set<Platform>? = null,
        modules: Set<String>? = null,
        buildTypes: Set<BuildType>? = null,
        formats: Set<PackageTask.Format>? = null,
    ) {
        val possiblePlatforms = if (OsFamily.current.isMac) {
            Platform.leafPlatforms
        } else {
            // Apple targets could be packaged only on Mac OS X due to legal obstacles
            Platform.leafPlatforms.filter { !it.isDescendantOf(Platform.APPLE) }.toSet()
        }

        val platformsToPackage = platforms ?: possiblePlatforms
        val modulesToPackage = (modules?.map { model.getModuleByName(it) } ?: model.modules).toSet()
        val formatsToPackage = formats ?: PackageTask.Format.entries.toSet()

        val taskIds = taskGraph
            .tasks
            .filterIsInstance<PackageTask>()
            .filter {
                it.module in modulesToPackage &&
                (it.platform == null || it.platform in platformsToPackage) &&
                it.format in formatsToPackage
            }.filterByBuildTypeAndReport(
                explicit = buildTypes,
                default = BuildType.Release,
            )
            .map { it.id }
            .toSet()

        if (taskIds.isEmpty()) {
            userReadableError("No package tasks were found")
        }

        logger.debug("Selected tasks to package: ${formatTaskNames(taskIds)}")
        taskExecutor.runTasksAndReportOnFailure(taskIds)
    }

    /**
     * Runs the given [task] and its dependencies, and throws an exception if any task fails.
     * If all tasks are successful, the result of the given [task] is returned.
     *
     * Use the [mode][TaskExecutor.mode] on this [TaskExecutor] to choose whether to fail fast or keep executing as many
     * tasks as possible in case of failure.
     *
     * @throws TaskExecutionFailed if any task fails with a non-[UserReadableError] exception.
     * @throws UserReadableError if the given [task] is not found in the current task graph, or if a task fails with a
     * [UserReadableError].
     */
    suspend fun runTask(task: TaskId): TaskResult = taskExecutor.runTasksAndReportOnFailure(setOf(task))[task]
        ?: error("Task '$task' was successfully executed but is not in the results map")

    /**
     * Runs the given set of [tasks] and their dependencies, and throws an exception if any task fails.
     * If all tasks are successful, the results of all tasks that were executed are returned as a map, including results
     * of the task dependencies.
     *
     * @see runTasksAndReportOnFailure
     */
    suspend fun runTasks(tasks: Set<TaskId>): Map<TaskId, TaskResult> = taskExecutor.runTasksAndReportOnFailure(tasks)

    suspend fun publish(modules: Collection<AmperModule>, repositoryId: String) {
        if (repositoryId == MAVEN_CENTRAL_REPOSITORY_ID) {
            val nonPublishableModules = modules.filterNot { it.publishingSettings.mavenCentral.enabled }
            if (nonPublishableModules.isNotEmpty()) {
                userReadableError {
                    val moduleOrModules = if (nonPublishableModules.size > 1) "modules" else "module"
                    appendLine("The following $moduleOrModules cannot be published to Maven Central because " +
                            "`settings.publishing.mavenCentral` is not enabled:")
                    nonPublishableModules.forEach {
                        appendLine("  - ${it.userReadableName}")
                    }
                }
            }
        } else {
            for (module in modules) {
                if (module.mavenPublishRepositories.none { it.id == repositoryId }) {
                    if (module.mavenResolveRepositories.any { it.id == repositoryId }) {
                        // TODO: Include trace info here?
                        userReadableError(
                            "Cannot publish to repository '${repositoryId}' because it's not marked as publishable. " +
                                    "Please check your configuration and make sure that `publish: true` " +
                                    "is set for this repository."
                        )
                    } else {
                        userReadableError("Module '${module.userReadableName}' does not have repository with id '$repositoryId'")
                    }
                }
            }
        }

        val moduleNames = modules.mapTo(mutableSetOf()) { it.userReadableName }
        val publishTasks = taskGraph.tasks
            .filterIsInstance<PublishTask>()
            .filter { it.targetRepositoryId == repositoryId && it.module.userReadableName in moduleNames }
            .map { it.id }
            .toSet()

        if (publishTasks.isEmpty()) {
            userReadableError("No publish tasks were found for specified module and repository filters")
        }

        logger.debug("Selected tasks to publish: ${formatTaskNames(publishTasks)}")
        taskExecutor.runTasksAndReportOnFailure(publishTasks)
    }

    suspend fun test(
        includeModules: Set<String>?,
        requestedPlatforms: Set<Platform>?,
        excludeModules: Set<String>,
        buildType: BuildType?,
    ) {
        require(requestedPlatforms == null || requestedPlatforms.isNotEmpty())

        val moduleNamesToCheck = (includeModules ?: emptySet()) + excludeModules
        moduleNamesToCheck.forEach { model.getModuleByName(it) }

        requestedPlatforms
            ?.filter { it !in currentHostRunnablePlatforms }
            ?.takeIf { it.isNotEmpty() }
            ?.let { unsupportedPlatforms ->
                val message = """
                    Unable to run requested platform(s) on the current system.
                    
                    Requested unsupported platforms: ${formatPlatforms(unsupportedPlatforms)}
                    Runnable platforms on the current system: ${formatPlatforms(currentHostRunnablePlatforms)}
                """.trimIndent()
                userReadableError(message)
            }

        val allTestTasks = taskGraph.tasks.filterIsInstance<TestTask>()
        if (allTestTasks.isEmpty()) {
            userReadableError("No test tasks were found in the entire project")
        }

        val platformTestTasks = allTestTasks
            .filter { it.platform in (requestedPlatforms ?: currentHostRunnablePlatforms) }
            .filterByBuildTypeAndReport(
                explicit = buildType?.let(::setOf),
                default = BuildType.Debug,
            )
        requestedPlatforms?.filter { requestedPlatform ->
            platformTestTasks.none { it.platform == requestedPlatform }
        }?.takeIf { it.isNotEmpty() }?.let { platformsWithMissingTests ->
            userReadableError("No test tasks were found for platforms: " +
                    platformsWithMissingTests.map { it.name }.sorted().joinToString(" ")
            )
        }

        val includedTestTasks = if (includeModules != null) {
            platformTestTasks.filter { task -> includeModules.contains(task.module.userReadableName) }
        } else {
            platformTestTasks
        }
        if (includedTestTasks.isEmpty()) {
            userReadableError("No test tasks were found for specified include filters")
        }
        if (runSettings.userJvmArgs.isNotEmpty() &&
            includedTestTasks.none { it.platform in setOf(Platform.JVM, Platform.ANDROID) }
        ) {
            logger.warn("The $UserJvmArgsOption option has no effect when running only non-JVM tests")
        }

        val testTasks = includedTestTasks
            .filter { task -> !excludeModules.contains(task.module.userReadableName) }
            .map { it.id }
            .toSet()
        if (testTasks.isEmpty()) {
            userReadableError("No test tasks were found after applying exclude filters")
        }

        taskExecutor.runTasksAndReportOnFailure(testTasks)
    }

    /**
     * Called by the 'check' command.
     * Runs checks in the project.
     *
     * The builtin check is "tests", which runs all tests (equivalent to `amper test`).
     * Custom checks are contributed by plugins via [org.jetbrains.amper.frontend.plugins.CheckFromPlugin].
     *
     * If [modules] is specified, only checks for those modules are run.
     * If [checkNames] is specified, only those specific checks are run.
     * If [skip] is specified, those checks are skipped (incompatible with [checkNames]).
     */
    suspend fun check(
        modules: Set<String>? = null,
        checkNames: Set<String>? = null,
        skip: Set<String> = emptySet(),
        // TODO: arguments for tests, like buildType, filter, etc.
    ) {
        val selectedModules = modules?.map { model.getModuleByName(it) }?.toSet() ?: model.modules.toSet()

        val allChecks = listOf(CheckEntry.Tests) + selectedModules.flatMap { it.checksFromPlugins }
            .map(CheckEntry::Custom)

        // Resolve all user-provided names to qualified names
        val selectedChecks = checkNames?.flatMapTo(mutableSetOf()) {
            resolveMatchingEntities(it, allChecks, "check")
        }
        val skippedChecks = skip.flatMapTo(mutableSetOf()) {
            resolveMatchingEntities(it, allChecks, "check", " in --skip")
        }

        val effectiveChecks = selectedChecks ?: (allChecks - skippedChecks)

        val taskIdsToRun = effectiveChecks.flatMap { check ->
            when (check) {
                is CheckEntry.Custom -> listOf(check.custom.performedBy)
                CheckEntry.Tests ->
                    taskGraph.tasks.filterIsInstance<TestTask>().filter {
                        it.module in selectedModules && it.platform in currentHostRunnablePlatforms
                    }.map { it.id }
            }
        }.toSet()

        if (taskIdsToRun.isEmpty()) {
            userReadableError("No checks were found for the specified filters")
        }

        taskExecutor.runTasksAndReportOnFailure(taskIdsToRun)
    }

    /**
     * Called by the 'do' command.
     * Runs custom commands in the project.
     *
     * @param modules If specified, only run commands in these modules.
     * @param commandName The name of the command to run.
     */
    suspend fun doCustomCommand(
        modules: Set<String>? = null,
        commandName: String,
    ) {
        data class CustomCommandEntry(
            val custom: CustomCommandFromPlugin,
        ) : QualifiedEntity {
            override val name = QualifiedName(custom.name, custom.pluginId.value)
        }

        val selectedModules = modules?.map { model.getModuleByName(it) }?.toSet() ?: model.modules.toSet()

        val allCustomCommands = selectedModules.flatMap { it.customCommandsFromPlugins }.map(::CustomCommandEntry)

        val resolvedCommands = resolveMatchingEntities(
            userProvidedName = commandName,
            entities = allCustomCommands,
            entityDisplayName = "command",
        ).mapTo(mutableSetOf()) { it.custom.performedBy }

        taskExecutor.runTasksAndReportOnFailure(resolvedCommands)
    }

    suspend fun rebuildJvmAppForHotReload(
        module: AmperModule,
    ) : List<IncrementalCache.Change> {
        require(module.type == ProductType.JVM_APP)

        val taskIds = buildSet {
            add(CommonTaskType.Compile.getTaskName(module, Platform.JVM).id)
            module.getModuleDependencies(
                isTest = false,
                Platform.JVM,
                ResolutionScope.RUNTIME,
                context.userCacheRoot,
                context.incrementalCache
            ).forEach { add(CommonTaskType.Compile.getTaskName(it, Platform.JVM).id) }
        }
        return runTasks(taskIds).values
            .filterIsInstance<JvmCompileTask.Result>()
            .flatMap { result -> result.changes }
    }

    suspend fun runApplication(target: RunTarget) {
        runApplication(target.module, target.platform, target.buildType)
    }

    suspend fun runApplication(moduleToRun: AmperModule, platform: Platform, buildType: BuildType?) {
        if (platform !in moduleToRun.leafPlatforms) {
            userReadableError("""
                Platform '${platform.pretty}' is not found for module '${moduleToRun.userReadableName}'.

                Available platforms: ${formatPlatforms(moduleToRun.leafPlatforms)}
            """.trimIndent())
        }
        if (runSettings.composeHotReloadMode && !isComposeEnabledFor(moduleToRun)) {
            userReadableError("Compose must be enabled to use Compose Hot Reload mode")
        }

        val moduleRunTasks = taskGraph.tasks
            .filterIsInstance<RunTask>()
            .filterIf(runSettings.composeHotReloadMode) { it is JvmHotRunTask }
            .filter { it.module == moduleToRun && it.platform == platform }
            .filterByBuildTypeAndReport(
                explicit = buildType?.let(::setOf),
                default = BuildType.Debug,
            )

        if (moduleRunTasks.isEmpty()) {
            when (val t = moduleToRun.type) {
                ProductType.JS_APP -> errorNonRunnableModuleType(moduleToRun, "${AmperBuild.documentationUrl}/user-guide/product-types/js-app/#running-your-application")
                ProductType.WASM_WASI_APP -> errorNonRunnableModuleType(moduleToRun, "${AmperBuild.documentationUrl}/user-guide/product-types/wasm-app/#running-your-application")
                else if t.isLibrary() -> userReadableError(
                    "Module '${moduleToRun.userReadableName}' cannot be run with the 'run' command because it's a " +
                            "library module. Please use an application product type.\n" +
                            "See the documentation for more info:\n" +
                            "${AmperBuild.documentationUrl}/user-guide/product-types",
                )
                else -> userReadableError("No run tasks are available for module '${moduleToRun.userReadableName}' and platform '${platform.pretty}'")
            }
        }

        val task = moduleRunTasks.singleOrNull()
            ?: error("Multiple run tasks match the selected module '${moduleToRun.userReadableName}' " +
                    "and platform '${platform.pretty}'")

        runTask(task.id)
    }

    private fun errorNonRunnableModuleType(
        module: AmperModule,
        documentationUrl: String,
    ): Nothing {
        userReadableError(
            "Module '${module.userReadableName}' of type '${module.type.value}' cannot be run directly by the Kotlin Toolchain at the moment.\n" +
                    "See the documentation for more info:\n$documentationUrl",
        )
    }

    /**
     * Run the iOS pre-build task identified by the [platform], [buildType] and the module.
     * The module is identified by its [moduleDir].
     *
     * @return the name of the module with the [moduleDir].
     */
    suspend fun prebuildForXcode(
        moduleDir: Path,
        platform: Platform,
        buildType: BuildType,
    ): IosPreBuildTask.Result {
        val module = model.modules.find { it.source.moduleDir == moduleDir }
        requireNotNull(module) {
            "Unable to resolve a module with the module directory '$moduleDir'"
        }

        if (platform !in module.leafPlatforms) {
            val availablePlatformsForModule = module.leafPlatforms.sorted().joinToString(" ")
            userReadableError("""
                    Platform '${platform.pretty}' is not found for iOS module '${module.userReadableName}'.
                    The module has declared platforms: $availablePlatformsForModule.
                    Please declare the required platform explicitly in the module's file.
                """.trimIndent())
        }

        runTask(
            ModuleTaskTypes.IntegrateSwiftPMPackageIfNeeded.getTaskName(
                module = module,
            ).id
        )

        val taskId = IosTaskType.PreBuildIosApp.getTaskName(
            module = module,
            platform = platform,
            isTest = false,
            buildType = buildType,
        ).id
        // If this cast fails, it should be an internal error anyway, no need for special handling
        return runTask(taskId) as IosPreBuildTask.Result
    }

    /**
     * @see org.jetbrains.amper.cli.commands.ide.GenerateKlibsCommand
     */
    suspend fun generateKlibsForIde() {
        val taskIds = taskGraph.tasks
            .filterIsInstance<GenerateKlibsForIdeTask>()
            .mapTo(mutableSetOf(), Task::id)
        if (taskIds.isEmpty()) return // silently
        runTasks(taskIds)
    }

    /**
     * @see org.jetbrains.amper.cli.commands.ide.PrepareComposeResourcesCommand
     */
    suspend fun prepareComposeResourcesForIde() {
        val taskIds = taskGraph.tasks
            .filter {
                // PrepareResources that lays out Compose resources in the generated `preparedComposeResources`
                // directory is a dependency of GenerateResourceAccessorsTask.
                it is GenerateResourceAccessorsTask || it is GenerateResClassTask
            }
            .mapTo(mutableSetOf(), Task::id)
        if (taskIds.isEmpty()) return
        runTasks(taskIds)
    }

    private fun formatTaskNames(publishTasks: Collection<TaskId>) =
        publishTasks.map { it.value }.sorted().joinToString(" ")

    private fun <T> List<T>.filterByBuildTypeAndReport(
        explicit: Set<BuildType>?,
        default: BuildType,
    ): List<T> where T : MaybeBuildTypeAware, T : MaybePlatformAware {
        return if (explicit != null) {
            require(explicit.isNotEmpty())
            val matchingTasks = filter { it.buildType == null || it.buildType in explicit }
            if (matchingTasks.isNotEmpty() && matchingTasks.all { it.buildType == null }) {
                val allPlatforms = matchingTasks.mapNotNull(MaybePlatformAware::platform).distinct()
                logger.warn("Explicit -v/--variant argument is ignored because " +
                        "none of the selected platforms (${formatPlatforms(allPlatforms)}) support build variants.")
            }
            matchingTasks
        } else {
            filter { it.buildType == null || it.buildType == default }
        }
    }

    private val logger: Logger = LoggerFactory.getLogger(javaClass)
}
