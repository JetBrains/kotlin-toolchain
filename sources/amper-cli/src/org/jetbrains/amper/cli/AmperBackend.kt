/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.options.UserJvmArgsOption
import org.jetbrains.amper.cli.widgets.TaskProgressRenderer
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.MaybeBuildTypeAware
import org.jetbrains.amper.engine.MaybePlatformAware
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.engine.TaskExecutor.TaskExecutionFailed
import org.jetbrains.amper.engine.TaskGraph
import org.jetbrains.amper.engine.TestTask
import org.jetbrains.amper.engine.runTasksAndReportOnFailure
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.mavenPublishRepositories
import org.jetbrains.amper.frontend.plugins.CustomCommandFromPlugin
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.tasks.ios.IosPreBuildTask
import org.jetbrains.amper.tasks.ios.IosTaskType
import org.jetbrains.amper.tasks.jvm.JvmHotRunTask
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import org.jetbrains.amper.util.BuildType
import org.jetbrains.amper.util.runnablePlatforms
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

class AmperBackend(
    val context: CliContext,
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
     * Defines how other tasks are executed if a task fails.
     */
    val taskExecutionMode: TaskExecutor.Mode = TaskExecutor.Mode.FAIL_FAST,
    /**
     * Background scope is terminated when project-related activities are finished (e.g., on Amper exit)
     */
    val backgroundScope: CoroutineScope,
) {
    private val modulesByName by lazy {
        model.modules.associateBy { it.userReadableName }
    }

    internal val taskGraph: TaskGraph by lazy {
        spanBuilder("Build task graph").useWithoutCoroutines {
            ProjectTasksBuilder(context = context, model = model, runSettings = runSettings).build()
        }
    }

    private val taskExecutor: TaskExecutor by lazy {
        TaskExecutor(taskGraph, taskExecutionMode) {
            TaskProgressRenderer(
                terminal = context.terminal,
                coroutineScope = backgroundScope,
                executionPlan = it,
            )
        }
    }

    /**
     * The leaf [Platform]s that can be "run" from the current host.
     *
     * That is, code compiled to the returned [Platform]s can be run in any of the following ways:
     * * on this host directly
     * * in an emulator that runs on this host
     * * on a suitable physical device connected to this host
     *
     * Note: the actual presence of connected physical devices is not checked here.
     * Platforms that correspond to connected devices are returned as part of the set based on whether it would be
     * _possible_ to run them, should a suitable device be connected.
     */
    val runnablePlatforms: Set<Platform> by lazy { SystemInfo.CurrentHost.runnablePlatforms() }

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
        if (platforms != null) {
            logger.info("Compiling for platforms: ${platforms.map { it.name }.sorted().joinToString(" ")}")
        }
        if (modules != null) {
            logger.info("Compiling modules: ${modules.sorted().joinToString(" ")}")
        }

        val possibleCompilationPlatforms = if (OsFamily.current.isMac) {
            Platform.leafPlatforms
        } else {
            // Apple targets could be compiled only on Mac OS X due to legal obstacles
            Platform.leafPlatforms.filter { !it.isDescendantOf(Platform.APPLE) }.toSet()
        }

        val platformsToCompile = platforms ?: possibleCompilationPlatforms
        val modulesToCompile = (modules?.map { resolveModule(it) } ?: model.modules).toSet()

        val taskNames = taskGraph
            .tasks
            .filterIsInstance<BuildTask>()
            .filter {
                it.platform in platformsToCompile && it.module in modulesToCompile
            }
            .filterByBuildTypeAndReport(
                explicit = buildTypes,
                default = BuildType.Debug,
            )
            .map { it.taskName }
            .toSet()
        logger.debug("Selected tasks to compile: ${formatTaskNames(taskNames)}")

        if (taskNames.isEmpty()) {
            // TODO: Give more info on why there is nothing to build
            logger.warn("Nothing to build")
            return
        }
        taskExecutor.runTasksAndReportOnFailure(taskNames)
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
        if (platforms != null) {
            logger.info("Packaging for platforms: ${platforms.map { it.name }.sorted().joinToString(" ")}")
        }
        if (modules != null) {
            logger.info("Packaging modules: ${modules.sorted().joinToString(" ")}")
        }
        if (buildTypes != null) {
            logger.info("Packaging variants: ${buildTypes.map { it.value }.sorted().joinToString(" ")}")
        }
        if (formats != null) {
            logger.info("Packaging formats: ${formats.map { it.value }.sorted().joinToString(" ")}")
        }

        val possiblePlatforms = if (OsFamily.current.isMac) {
            Platform.leafPlatforms
        } else {
            // Apple targets could be packaged only on Mac OS X due to legal obstacles
            Platform.leafPlatforms.filter { !it.isDescendantOf(Platform.APPLE) }.toSet()
        }

        val platformsToPackage = platforms ?: possiblePlatforms
        val modulesToPackage = (modules?.map { resolveModule(it) } ?: model.modules).toSet()
        val formatsToPackage = formats ?: PackageTask.Format.entries.toSet()

        val taskNames = taskGraph
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
            .map { it.taskName }
            .toSet()

        if (taskNames.isEmpty()) {
            userReadableError("No package tasks were found")
        }

        logger.debug("Selected tasks to package: ${formatTaskNames(taskNames)}")
        taskExecutor.runTasksAndReportOnFailure(taskNames)
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
    suspend fun runTask(task: TaskName): TaskResult = taskExecutor.runTasksAndReportOnFailure(setOf(task))[task]
        ?: error("Task '$task' was successfully executed but is not in the results map")

    /**
     * Runs the given set of [tasks] and their dependencies, and throws an exception if any task fails.
     * If all tasks are successful, the results of all tasks that were executed are returned as a map, including results
     * of the task dependencies.
     *
     * @see runTasksAndReportOnFailure
     */
    suspend fun runTasks(tasks: Set<TaskName>): Map<TaskName, TaskResult> = taskExecutor.runTasksAndReportOnFailure(tasks)

    suspend fun publish(modules: Set<String>?, repositoryId: String) {
        require(modules == null || modules.isNotEmpty())

        if (modules != null) {
            for (moduleName in modules) {
                val module = resolveModule(moduleName)
                if (module.mavenPublishRepositories.none { it.id == repositoryId }) {
                    userReadableError("Module '$moduleName' does not have repository with id '$repositoryId' having flag 'publish=true'")
                }
            }
        }

        val publishTasks = taskGraph.tasks
            .filterIsInstance<PublishTask>()
            .filter { it.targetRepositoryId == repositoryId }
            .filter { modules == null || modules.contains(it.module.userReadableName) }
            .map { it.taskName }
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
        moduleNamesToCheck.forEach { resolveModule(it) }

        requestedPlatforms
            ?.filter { it !in runnablePlatforms }
            ?.takeIf { it.isNotEmpty() }
            ?.let { unsupportedPlatforms ->
                val message = """
                    Unable to run requested platform(s) on the current system.
                    
                    Requested unsupported platforms: ${formatPlatforms(unsupportedPlatforms)}
                    Runnable platforms on the current system: ${formatPlatforms(runnablePlatforms)}
                """.trimIndent()
                userReadableError(message)
            }

        val allTestTasks = taskGraph.tasks.filterIsInstance<TestTask>()
        if (allTestTasks.isEmpty()) {
            userReadableError("No test tasks were found in the entire project")
        }

        val platformTestTasks = allTestTasks
            .filter { it.platform in (requestedPlatforms ?: runnablePlatforms) }
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
            .map { it.taskName }
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
        val selectedModules = modules?.map { resolveModule(it) }?.toSet() ?: model.modules.toSet()

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

        val taskNamesToRun = effectiveChecks.flatMap { check ->
            when (check) {
                is CheckEntry.Custom -> listOf(check.custom.performedBy)
                CheckEntry.Tests ->
                    taskGraph.tasks.filterIsInstance<TestTask>().filter {
                        it.module in selectedModules && it.platform in runnablePlatforms
                    }.map { it.taskName }
            }
        }.toSet()

        if (taskNamesToRun.isEmpty()) {
            userReadableError("No checks were found for the specified filters")
        }

        taskExecutor.runTasksAndReportOnFailure(taskNamesToRun)
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

        val selectedModules = modules?.map { resolveModule(it) }?.toSet() ?: model.modules.toSet()

        val allCustomCommands = selectedModules.flatMap { it.customCommandsFromPlugins }.map(::CustomCommandEntry)

        val resolvedCommands = resolveMatchingEntities(
            userProvidedName = commandName,
            entities = allCustomCommands,
            entityDisplayName = "command",
        ).mapTo(mutableSetOf()) { it.custom.performedBy }

        taskExecutor.runTasksAndReportOnFailure(resolvedCommands)
    }

    suspend fun runApplication(moduleName: String?, platform: Platform?, buildType: BuildType?) {
        val moduleToRun = when {
            moduleName != null -> resolveModule(moduleName)
            // The special case for single module projects is only here to have different error messages
            model.modules.size == 1 -> model.modules.single()
            else -> findSingleRunnableAppModule(platform)
        }
        val platformToRun = platform?.also { checkExplicitPlatformCanBeRun(it) }
            ?: findSingleRunnablePlatform(moduleToRun)

        if (platformToRun !in moduleToRun.leafPlatforms) {
            userReadableError("""
                Platform '${platformToRun.pretty}' is not found for module '${moduleToRun.userReadableName}'.

                Available platforms: ${formatPlatforms(moduleToRun.leafPlatforms)}
            """.trimIndent())
        }
        if (runSettings.composeHotReloadMode && !isComposeEnabledFor(moduleToRun)) {
            userReadableError("Compose must be enabled to use Compose Hot Reload mode")
        }
        if (runSettings.userJvmArgs.isNotEmpty() && platformToRun != Platform.JVM) {
            logger.warn("The $UserJvmArgsOption option have no effect when running a non-JVM app")
        }

        val moduleRunTasks = taskGraph.tasks
            .filterIsInstance<RunTask>()
            .filter { runSettings.composeHotReloadMode == it is JvmHotRunTask }
            .filter { it.module == moduleToRun && it.platform == platformToRun }
            .filterByBuildTypeAndReport(
                explicit = buildType?.let(::setOf),
                default = BuildType.Debug,
            )

        if (moduleRunTasks.isEmpty()) {
            when (val t = moduleToRun.type) {
                ProductType.JS_APP -> errorNonRunnableModuleType(moduleToRun, "${AmperBuild.documentationUrl}/user-guide/product-types/js-app/#running-your-application")
                ProductType.WASM_JS_APP,
                ProductType.WASM_WASI_APP -> errorNonRunnableModuleType(moduleToRun, "${AmperBuild.documentationUrl}/user-guide/product-types/wasm-app/#running-your-application")
                else if t.isLibrary() -> userReadableError(
                    "Module '${moduleToRun.userReadableName}' cannot be run with the 'run' command because it's a " +
                            "library module. Please use an application product type.\n" +
                            "See the documentation for more info:\n" +
                            "${AmperBuild.documentationUrl}/user-guide/product-types",
                )
                else -> userReadableError("No run tasks are available for module '${moduleToRun.userReadableName}' and platform '${platformToRun.pretty}'")
            }
        }

        val task = moduleRunTasks.singleOrNull()
            ?: error("Multiple run tasks match the selected module '${moduleToRun.userReadableName}' " +
                    "and platform '${platformToRun.pretty}'")

        runTask(task.taskName)
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

    private fun findSingleRunnableAppModule(platform: Platform?): AmperModule {
        val appModules = model.modules
            .filter { it.type.isApplication() }
            .ifEmpty {
                userReadableError("There are no application modules in the project, nothing to run")
            }
        val appModulesMatchingCommand = appModules
            .filter { !runSettings.composeHotReloadMode || it.type == ProductType.JVM_APP }
            .ifEmpty {
                userReadableError(
                    "There are no JVM application modules in the project, and only those support Compose Hot Reload.\n\n" +
                            "Available application modules and their platforms:\n" +
                            appModules.joinToString("\n") { "  ${it.userReadableName}: ${formatPlatforms(it.leafPlatforms)}" },
                )
            }
            .filter { runSettings.deviceId == null || it.supportsDeviceIdSelection() }
            .ifEmpty {
                userReadableError(
                    "There are no Android or iOS application modules in the project, and only those support " +
                            "selecting a device or emulator explicitly. Please remove the '--device-id' option.\n\n" +
                            "Available application modules and their type:\n" +
                            appModules.joinToString("\n") { "  ${it.userReadableName}: ${it.type.value}" },
                )
            }
            // we check platforms after so that the error messages make sense (they are easier to write this way)
            .filter { platform == null || platform in it.leafPlatforms }
            .ifEmpty {
                userReadableError {
                    // Note that platform can't be null here (otherwise we would not have filtered out the last modules)
                    append("There are no application modules in the project that support the '${platform?.pretty}' platform")
                    // The double "and" might be awkward if both --device-id and --compose-hot-reload-mode are passed,
                    // but it's technically correct, and will realistically never happen, so let's not complicate.
                    if (runSettings.deviceId != null) {
                        append(" and device selection with --device-id")
                    }
                    if (runSettings.composeHotReloadMode) {
                        append(" and Compose Hot Reload")
                    }
                    appendLine(".")
                    appendLine()
                    appendLine("Available application modules and their platforms:")
                    appModules.sortedBy { it.userReadableName }.forEach { module ->
                        appendLine("  ${module.userReadableName}: ${formatPlatforms(module.leafPlatforms)}")
                    }
                }
            }

        // We don't check this earlier because if there are no modules with the given platform at all,
        // it's a more important error because the command doesn't work for this project on any host.
        if (platform != null && platform !in runnablePlatforms) {
            userReadableError("Code compiled for the '${platform.pretty}' platform cannot be run from the current host")
        }
        val runnableCandidatesIgnoringDeviceSelection = appModulesMatchingCommand
            .filter { it.canBeRunFromCurrentHost() }
            .ifEmpty {
                userReadableError(
                    "There are no application modules in the project that can be run from the current host.\n\n" +
                            "Runnable platforms on this host: ${formatPlatforms(runnablePlatforms)}",
                )
            }
        val runnableCandidates = runnableCandidatesIgnoringDeviceSelection
            .filter { runSettings.deviceId != null || !it.requiresPhysicalDeviceToRunFromCurrentHost() }
            .ifEmpty {
                if (runnableCandidatesIgnoringDeviceSelection.size > 1) {
                    userReadableError(
                        "All runnable application modules in the project require selecting a physical device with " +
                                "'--device-id'."
                    )
                } else {
                    userReadableError(
                        "The only runnable application module " +
                                "'${runnableCandidatesIgnoringDeviceSelection.single().userReadableName}' requires " +
                                "selecting a physical device with '--device-id'."
                    )
                }
            }
        if (runnableCandidates.size > 1) {
            val canBeSelectedUsingPlatform = runnableCandidates
                .flatMap { it.leafPlatforms intersect runnablePlatforms }
                .let { allPlatformEntries ->
                    // Check if there are no such two app modules that share a leaf platform
                    allPlatformEntries.distinct().size == allPlatformEntries.size
                }

            userReadableError {
                append("There are several matching application modules in the project. Please specify one with the ")
                if (canBeSelectedUsingPlatform && platform == null) {
                    append("'--platform' or '--module'")
                } else {
                    append("'--module'")
                }
                appendLine(" option.")
                appendLine()
                append("Runnable application modules")
                if (platform != null) {
                    append(" supporting the '${platform.pretty}' platform")
                }
                appendLine(":")
                runnableCandidates.sortedBy { it.userReadableName }.forEach { module ->
                    append("  ${module.userReadableName}")
                    if (canBeSelectedUsingPlatform) {
                        append(": ${formatPlatforms(module.leafPlatforms)}")
                    }
                    appendLine()
                }
            }
        }
        return runnableCandidates.single()
    }

    private fun checkExplicitPlatformCanBeRun(platform: Platform) {
        if (platform !in runnablePlatforms) {
            userReadableError("Code compiled for the '${platform.pretty}' platform cannot be run from the current host")
        }
        if (runSettings.composeHotReloadMode && platform != Platform.JVM) {
            userReadableError(
                "Compose Hot Reload only supports the JVM platform and cannot work with '${platform.pretty}'. " +
                        "Please remove the '--compose-hot-reload-mode' or the '--platform' option."
            )
        }
        if (runSettings.deviceId != null && !platform.supportsDeviceSelection) {
            userReadableError(
                "Platform '${platform.pretty}' does not support device selection with --device-id. " +
                        "Please remove the option."
            )
        }
        if (runSettings.deviceId == null && platform.requiresPhysicalDeviceSelection) {
            userReadableError(
                "Platform '${platform.pretty}' requires selecting a physical device. " +
                        "Please provide the --device-id option."
            )
        }
    }

    private fun findSingleRunnablePlatform(moduleToRun: AmperModule): Platform {
        val runnablePlatformsIgnoringDeviceId = (moduleToRun.leafPlatforms intersect runnablePlatforms)
            .ifEmpty {
                userReadableError(
                    "None of the platforms of module '${moduleToRun.userReadableName}' can be run from " +
                            "the current host.\n\n" +
                            "Current platforms: ${formatModulePlatforms(moduleToRun)}"
                )
            }
        val effectivelyRunnablePlatforms = runnablePlatformsIgnoringDeviceId
            .filter { !runSettings.composeHotReloadMode || it == Platform.JVM }
            .ifEmpty {
                userReadableError(
                    "Module '${moduleToRun.userReadableName}' doesn't support Compose Hot Reload because it's not a " +
                            "JVM application. Please remove the --compose-hot-reload-mode option."
                )
            }
            .filter { runSettings.deviceId == null || it.supportsDeviceSelection }
            .ifEmpty {
                val platformsAreFiltered = runnablePlatformsIgnoringDeviceId.size < moduleToRun.leafPlatforms.size
                userReadableError {
                    append("No platforms of module '${moduleToRun.userReadableName}'")
                    if (platformsAreFiltered) {
                        append(" that are runnable on this host")
                    }
                    appendLine(" support device selection with --device-id. Please remove the option.")
                    appendLine()
                    appendLine("Current platforms: ${formatModulePlatforms(moduleToRun)}")
                    if (platformsAreFiltered) {
                        appendLine("Runnable on this host: ${formatPlatforms(runnablePlatformsIgnoringDeviceId)}")
                    }
                }
            }
            .filter { runSettings.deviceId != null || !it.requiresPhysicalDeviceSelection }
            .ifEmpty {
                userReadableError("Please select a physical device to run module '${moduleToRun.userReadableName}' " +
                        "with --device-id.")
            }
        if (effectivelyRunnablePlatforms.size > 1) {
            // special case where there is definitely a preference to avoid Rosetta
            if (effectivelyRunnablePlatforms.toSet() == setOf(Platform.MACOS_X64, Platform.MACOS_ARM64)
                && SystemInfo.CurrentHost.family.isMac
                && SystemInfo.CurrentHost.arch == Arch.Arm64
            ) {
                return Platform.MACOS_ARM64
            }
            // we still list runnablePlatformsIgnoringDeviceId in the error, because we don't know if the user will
            // add --device-id in the next try
            userReadableError("""
                Multiple platforms are available to run in module '${moduleToRun.userReadableName}'.
                Please specify one with '--platform' argument.

                Runnable on this host: ${formatPlatforms(runnablePlatformsIgnoringDeviceId)}
            """.trimIndent())
        }
        return effectivelyRunnablePlatforms.single()
    }

    private fun AmperModule.canBeRunFromCurrentHost(): Boolean = leafPlatforms.any { it in runnablePlatforms }

    private fun AmperModule.supportsDeviceIdSelection(): Boolean = leafPlatforms.any { it.supportsDeviceSelection }

    private fun AmperModule.requiresPhysicalDeviceToRunFromCurrentHost(): Boolean =
        (leafPlatforms intersect runnablePlatforms).all { it.requiresPhysicalDeviceSelection }

    private val Platform.supportsDeviceSelection: Boolean
        get() = this == Platform.ANDROID || isDescendantOf(Platform.IOS)

    private val Platform.requiresPhysicalDeviceSelection: Boolean
        get() = isAppleDevice

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

        val taskName = IosTaskType.PreBuildIosApp.getTaskName(
            module = module,
            platform = platform,
            isTest = false,
            buildType = buildType,
        )
        // If this cast fails, it should be an internal error anyway, no need for special handling
        return runTask(taskName) as IosPreBuildTask.Result
    }

    private fun resolveModule(moduleName: String) = modulesByName[moduleName] ?: userReadableError(
        "Unable to resolve module by name '$moduleName'.\n\n" +
                "Available modules: ${formatModules(model.modules)}"
    )

    private fun formatModules(modules: Collection<AmperModule>) =
        modules.map { it.userReadableName }.sorted().joinToString(" ")

    private fun formatTaskNames(publishTasks: Collection<TaskName>) =
        publishTasks.map { it.name }.sorted().joinToString(" ")

    private fun formatPlatforms(platforms: Collection<Platform>) =
        platforms.map { it.pretty }.sorted().joinToString(" ")

    private fun formatModulePlatforms(moduleToRun: AmperModule): String {
        return formatPlatforms(moduleToRun.leafPlatforms)
    }

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
