/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Deferred
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.apprun.RunTarget
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.context.copyWithNewProjectContext
import org.jetbrains.amper.cli.context.findProjectContext
import org.jetbrains.amper.cli.formatModulePlatforms
import org.jetbrains.amper.cli.formatPlatforms
import org.jetbrains.amper.cli.getModuleByName
import org.jetbrains.amper.cli.options.UserJvmArgsOption
import org.jetbrains.amper.cli.options.buildTypeOption
import org.jetbrains.amper.cli.options.leafPlatformOption
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.options.userJvmArgsOption
import org.jetbrains.amper.cli.project.preparePluginsAndReadModel
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.cli.terminal.promptModuleSelection
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.compose.reload.HotReloadDelegate
import org.jetbrains.amper.compose.reload.HotReloadLoop
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.stdlib.collections.filterIf
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.ComposeHotReloadSettings
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.util.currentHostRunnablePlatforms
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.ifEmpty
import kotlin.collections.joinToString
import kotlin.collections.single
import kotlin.collections.sortedBy
import kotlin.collections.toSet
import kotlin.io.path.Path

internal class RunCommand : AmperProjectAwareCommand(name = "run") {

    private val module by moduleOption(
        help = "Specific module to run (run the `show modules` command to get the modules list)",
    )

    private val platform by leafPlatformOption(
        help = "Run the app on specified platform. This option is only necessary if the module has multiple main " +
                "functions for different platforms",
    )

    private val deviceId by option(
        "-d", "--device-id",
        help = """
            Platform specific device ID of the device to install and run on. 
            Only Android and iOS platforms are currently supported.
            - Android: use `adb devices` command to list connected devices and emulators
            - iOS: use `xcrun devicectl list devices` command to list available devices or `xcrun simctl list devices` to list available simulators.
        """.trimIndent(),
    )

    private val variant by buildTypeOption(
        help = "Run the specified variant of the app. Debug variant is launched by default.",
    )

    private val jvmArgs by userJvmArgsOption(
        help = """
            The JVM arguments to pass to the JVM running the application, separated by spaces.
            These arguments only affect the JVM used to run the application, and don't affect non-JVM applications.
            
            If the `${UserJvmArgsOption}` option is repeated, the arguments contained in all occurrences are passed
            to the JVM in the order they were specified. The JVM decides how it handles duplicate arguments.
        """.trimIndent()
    )

    private val jvmMainClass by option("--main-class", help = "The fully-qualified name of the main class to run. " +
            "This option is only applicable for JVM applications. By default, the main class is read from the module " +
            "configuration file, or is determined automatically by convention, searching for a main.kt file.")

    private val workingDir by option("--working-dir", help = "The working directory for the application run. " +
            "By default, the current directory is used. This option is only applicable for JVM and native desktop " +
            "applications, and Kotlin scripts. The working directory is not customizable for web applications or " +
            "mobile emulator runs.")
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .default(Path("."))

    private val composeHotReloadMode by option(COMPOSE_HOT_RELOAD_OPTION_NAME, help = "Enable Compose Hot Reload " +
            "mode for Compose Multiplatform applications (for desktop applications and libraries which have jvm platform). " +
            "This mode makes the application reloadable while running, which significantly reduces the development round-trip" +
            " to see code changes in action. \n\n" +
            "Note: in this mode, the Java runtime is overridden to the JetBrains Runtime, which is required for Compose Hot Reload to work.")
        .flag()

    // TODO: Introduce "no filesystem watching" opt-out for compose hot reload as IDE can do it itself?

    private val port by option(
        "--port",
        help = """
            Run a server with web application on the specified port. Default port is 8080.
            
            Only Wasm/JS platform is currently supported.
        """.trimIndent(),
    ).int()

    private val openBrowser by option(
        "--open-browser",
        help = """
            Whether to open the default browser when running the web application.
            
            Only Wasm/JS platform is currently supported.
        """.trimIndent(),
    ).flag("--no-open-browser", default = true)

    private val programArguments by argument(name = "app_arguments").multiple()

    override fun help(context: Context): String =
        "Run an application module in your project, or a standalone Kotlin script"

    override fun helpEpilog(context: Context): String = """
        _Note: use `--` to separate the application's arguments from the Kotlin CLI options.
        It is required if any application argument looks like a CLI option (starts with '-')._
        
        **Example 1:** Run an application module of your project 
        (when there is only one, or only one that can run on the current host)
        ```
        kotlin run
        kotlin run -- arg1 arg2
        ```
        
        **Example 2:** Run a specific application module of your project (when there are several candidates):
        ```
        # Disambiguate using the module's name
        kotlin run -m my-module
        kotlin run -m my-module -- arg1 arg2
        
        # Disambiguate using the platform to run
        kotlin run -p jvm
        kotlin run -p android 
        ```
    """.trimIndent()

    override suspend fun run(cliContext: ProjectCliContext) {
        platform?.also { checkPlatformOptionConsistency(it) }

        val model = cliContext.preparePluginsAndReadModel()
        val target = model.selectRunTarget()
        if (composeHotReloadMode) {
            // If the configuration doesn't actually support hot-reload,
            // it will be diagnosed and the error will be thrown.
            HotReloadLoop.run(HotReloadDelegateImpl(cliContext, target))
        } else {
            withBackend(cliContext, model, runSettings = allRunSettings()) {
                it.runApplication(target)
            }
        }
    }

    /**
     * Checks whether the explicit [platform] option is consistent with other CLI options, without even looking at the
     * project model or the current host.
     * This function fails if the command line is incorrect and would never work on any machine, in any project.
     */
    private fun checkPlatformOptionConsistency(platform: Platform) {
        if (composeHotReloadMode && platform != Platform.JVM) {
            userReadableError(
                "Compose Hot Reload only supports the JVM platform and cannot work with '${platform.pretty}'. " +
                        "Please remove the '--compose-hot-reload-mode' or the '--platform' option."
            )
        }
        if (deviceId != null && !platform.supportsDeviceSelection) {
            userReadableError(
                "Platform '${platform.pretty}' does not support device selection with --device-id. " +
                        "Please remove the option or choose another platform."
            )
        }
        if (deviceId == null && platform.requiresPhysicalDeviceSelection) {
            userReadableError(
                "Platform '${platform.pretty}' requires selecting a physical device. " +
                        "Please provide the --device-id option or choose another platform."
            )
        }
    }

    private fun allRunSettings(
        composeHotReloadMode: ComposeHotReloadSettings? = null,
    ) = AllRunSettings(
        programArgs = programArguments,
        workingDir = workingDir,
        userJvmArgs = jvmArgs,
        userJvmMainClass = jvmMainClass,
        deviceId = deviceId,
        composeHotReloadSettings = composeHotReloadMode,
        port = port,
        openBrowser = openBrowser,
    )

    private inner class HotReloadDelegateImpl(
        private val initialCliContext: ProjectCliContext,
        private val target: RunTarget,
    ) : HotReloadDelegate<ProjectCliContext> {
        private var modelReloadCount = 0

        override suspend fun readModel() = runCatchingUserReadableError {
            val cliContext = if (modelReloadCount++ > 0) {
                /*
                 Reload the `AmperProjectContext` because it encodes the project structure that might have changed.

                 As refresh is not properly implemented in the default VFS implementation we use in CLI,
                  it also recreates the IJ Project instance which has new VirtualFile instances and Psi caches
                  to properly observe these potential changes.

                 IMPORTANT: We don't support project-root change in the hot reload loop,
                  as some machinery (logs, telemetry, default build directory) depends on it, and it doesn't make sense
                  to reinitialize them in the context of a single CLI call.
                 So if the user makes changes to the FS so that the root directory of the project is changed,
                  then this throws an error, as we use explicit directories detected/specified initially.
                */
                initialCliContext.copyWithNewProjectContext(
                    projectContext = context(initialCliContext.problemReporter) {
                        checkNotNull(
                            findProjectContext(
                                explicitProjectDir = initialCliContext.projectRoot.path,
                                explicitBuildDir = initialCliContext.buildOutputRoot.path,
                            )
                        ) { "Not reached: must not return null with explicit directories" }
                    }
                )
            } else initialCliContext

            val model = cliContext.preparePluginsAndReadModel()
            val module = model.modules.find { it.userReadableName == target.module.userReadableName }
                ?: userReadableError("Module '${target.module.userReadableName}' no longer exists in the project")
            if (Platform.JVM !in module.leafPlatforms) {
                userReadableError("Module '${target.module.userReadableName}' no longer targets the JVM platform, cannot use hot reload")
            }
            HotReloadLoop.State(
                cliContext = cliContext,
                model = model,
                hotApp = module,
            )
        }

        override suspend fun runApplication(
            state: HotReloadLoop.State<ProjectCliContext>,
            orchestrationPort: Deferred<Int>,
        ) = runCatchingUserReadableError {
            /*
             NOTE: Technically, this coroutine will be active throughout all the reloads, so
              multiple `rebuildClasses` are possible during this.

             This means that there could be two `AmperBackend` instances and task graphs active at the same time.
             It is not a very good precedent, as there should be a single active task graph at a time.
             Nothing bad is happening right now, but still.

             NOTE: When the actual "run app" code is no longer a task, this will stop being a problem,
              as the task graph will only be used for *build* and running is going to be detached from that.
            */
            withBackend(
                cliContext = state.cliContext,
                model = state.model,
                runSettings = allRunSettings(
                    composeHotReloadMode = ComposeHotReloadSettings(
                        orchestrationPort = orchestrationPort,
                    )
                ),
            ) {
                it.runApplication(moduleToRun = state.hotApp, platform = Platform.JVM, buildType = variant)
            }
        }

        override suspend fun rebuildClasses(
            state: HotReloadLoop.State<ProjectCliContext>,
        ) = runCatchingUserReadableError {
            withBackend(
                cliContext = state.cliContext,
                model = state.model,
                runSettings = allRunSettings(),
            ) {
                it.rebuildJvmAppForHotReload(module = state.hotApp)
            }
        }

        private inline fun <R> runCatchingUserReadableError(block: () -> R): Result<R> =
            // Catches only `UserReadableError` into the result, other exceptions are rethrown
            runCatching(block).onFailure { e -> if (e !is UserReadableError) throw e }
    }

    fun Model.selectRunTarget(): RunTarget {
        val moduleToRun = selectSingleRunnableAppModule()
        checkModuleIsRunnable(moduleToRun)
        val platformToRun = selectPlatformToRun(moduleToRun)

        if (composeHotReloadMode && !isComposeEnabledFor(moduleToRun)) {
            userReadableError("Compose must be enabled to use Compose Hot Reload mode")
        }
        if (jvmArgs.isNotEmpty() && platformToRun != Platform.JVM) {
            logger.warn("The $UserJvmArgsOption option have no effect when running a non-JVM app")
        }
        return RunTarget(moduleToRun, platformToRun, variant)
    }

    private fun Model.selectSingleRunnableAppModule(): AmperModule {
        if (module != null) {
            return getModuleByName(module!!)
        }
        if (modules.size == 1) {
            return modules.single()
        }
        val runnableCandidates = findRunnableAppModulesMatchingOptionsOrFail()
        if (runnableCandidates.size != 1) {
            if (!terminal.terminalInfo.interactive) {
                failOnAmbiguousModules(runnableCandidates)
            }
            return terminal.promptModuleSelection(
                promptMessage = "Multiple modules are available to run, please choose:",
                choices = runnableCandidates,
            )
        }
        return runnableCandidates.single()
    }

    /**
     * Finds all potentially runnable modules matching the CLI options: hot reload mode, device ID, platform, etc.
     *
     * Fails if no matching module is found with a precise error for which filter yielded no match.
     */
    private fun Model.findRunnableAppModulesMatchingOptionsOrFail(): List<AmperModule> {
        val appModules = modules
            .filter { it.type.isApplication() }
            .ifEmpty {
                userReadableError("There are no application modules in the project, nothing to run")
            }
        val appModulesMatchingCommand = appModules
            .filterIf(composeHotReloadMode) { it.type == ProductType.JVM_APP && isComposeEnabledFor(it) }
            .ifEmpty {
                userReadableError(
                    "There are no Compose JVM application modules in the project, and only those support Compose Hot Reload.\n\n" +
                            "Available application modules and their platforms:\n" +
                            appModules.joinToString("\n") { "  ${it.userReadableName}: ${formatPlatforms(it.leafPlatforms)}" },
                )
            }
            .filterIf(deviceId != null) { it.supportsDeviceIdSelection() }
            .ifEmpty {
                userReadableError(
                    "There are no Android or iOS application modules in the project, and only those support " +
                            "selecting a device or emulator explicitly. Please remove the '--device-id' option.\n\n" +
                            "Available application modules and their type:\n" +
                            appModules.joinToString("\n") { "  ${it.userReadableName}: ${it.type.value}" },
                )
            }
            // we check platforms after so that the error messages make sense (they are easier to write this way)
            .filterIf(platform != null) { platform in it.leafPlatforms }
            .ifEmpty {
                userReadableError {
                    // Note that platform can't be null here (otherwise we would not have filtered out the last modules)
                    append("There are no application modules in the project that support the '${platform?.pretty}' platform")
                    // The double "and" might be awkward if both --device-id and --compose-hot-reload-mode are passed,
                    // but it's technically correct, and will realistically never happen, so let's not complicate.
                    if (deviceId != null) {
                        append(" and device selection with --device-id")
                    }
                    if (composeHotReloadMode) {
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

        // We need to check this _after_ checking that at least some modules target the explicit platform. If no modules
        // target this platform, we need to report that error instead, because it means the command doesn't work for
        // this project on any host.
        // We also need to check this _before_ checking the modules that can run on the current host "in general".
        // Otherwise, the error message would be confusing: saying 'no modules can run' would be wrong if it's due to
        // the explicit platform filter).
        if (platform != null && platform !in currentHostRunnablePlatforms) {
            userReadableError("Code compiled for the '${platform!!.pretty}' platform cannot be run from the current host")
        }

        val runnableCandidatesIgnoringDeviceSelection = appModulesMatchingCommand
            .filter { it.canBeRunFromCurrentHost() }
            .ifEmpty {
                userReadableError(
                    "There are no application modules in the project that can be run from the current host.\n\n" +
                            "Runnable platforms on this host: ${formatPlatforms(currentHostRunnablePlatforms)}",
                )
            }
        val runnableCandidates = runnableCandidatesIgnoringDeviceSelection
            .filter { deviceId != null || !it.requiresPhysicalDeviceToRunFromCurrentHost() }
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
        return runnableCandidates
    }

    private fun Model.checkModuleIsRunnable(module: AmperModule) {
        // It's also OK to run libraries with a custom jvmMainClass (e.g. to test Compose components)
        if (module.type.isApplication() || jvmMainClass != null && (platform == Platform.JVM || (platform == null && Platform.JVM in module.leafPlatforms))) {
            return // all good
        }
        val applicationModules = modules.filter { it.type.isApplication() }
        userReadableError {
            appendLine(
                "Module '${module.userReadableName}' cannot be run because it is not an " +
                        "application module (its product type is '${module.type.schemaValue}')."
            )
            if (applicationModules.isEmpty()) {
                appendLine("""
                    There are actually no application modules in the project. To get something running, first create a module with an application product type.
                    See the documentation for more info: https://kotlin-toolchain.org/dev/user-guide/product-types
                """.trimIndent())
            } else {
                appendLine("You can instead pick one of the existing application modules of your project:")
                applicationModules.sortedBy { it.userReadableName }.forEach { module ->
                    appendLine("  - ${module.userReadableName}")
                }
            }
        }
    }

    private fun failOnAmbiguousModules(runnableCandidates: List<AmperModule>): Nothing {
        val canBeSelectedUsingPlatform = runnableCandidates
            .flatMap { it.leafPlatforms intersect currentHostRunnablePlatforms }
            .let { allPlatformEntries ->
                // Check if there are no such two app modules that share a leaf platform
                allPlatformEntries.distinct().size == allPlatformEntries.size
            }

        userReadableError {
            append("There are several")
            if (platform != null || deviceId != null || composeHotReloadMode) {
                append(" matching")
            }
            append(" application modules in the project. Please specify one with the '--module'")
            if (canBeSelectedUsingPlatform && platform == null) {
                append(" or '--platform'")
            }
            appendLine(" option.")
            appendLine()
            append("Runnable application modules")
            if (platform != null) {
                append(" supporting the '${platform!!.pretty}' platform")
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

    private fun selectPlatformToRun(moduleToRun: AmperModule): Platform {
        val explicitPlatform = platform
        if (explicitPlatform != null) {
            // This is already checked, but only when the module is not explicitly specified, so we need to re-check here
            if (explicitPlatform !in currentHostRunnablePlatforms) {
                userReadableError("Code compiled for the '${explicitPlatform.pretty}' platform cannot be run from the current host")
            }
            if (explicitPlatform !in moduleToRun.leafPlatforms) {
                userReadableError("""
                    Platform '${explicitPlatform.pretty}' is not found for module '${moduleToRun.userReadableName}'.
    
                    Available platforms: ${formatPlatforms(moduleToRun.leafPlatforms)}
                """.trimIndent())
            }
            return explicitPlatform
        }

        val runnablePlatformsIgnoringDeviceId = (moduleToRun.leafPlatforms intersect currentHostRunnablePlatforms)
            .ifEmpty {
                userReadableError(
                    "None of the platforms of module '${moduleToRun.userReadableName}' can be run from " +
                            "the current host.\n\n" +
                            "Current platforms: ${formatModulePlatforms(moduleToRun)}"
                )
            }
            .filterIf(composeHotReloadMode) { it == Platform.JVM }
            .ifEmpty {
                userReadableError(
                    "Module '${moduleToRun.userReadableName}' doesn't support Compose Hot Reload because it's not a " +
                            "JVM application. Please remove the --compose-hot-reload-mode option."
                )
            }

        val effectivelyRunnablePlatforms = runnablePlatformsIgnoringDeviceId
            .filterIf(deviceId != null) { it.supportsDeviceSelection }
            .ifEmpty {
                val platformsAreFiltered = runnablePlatformsIgnoringDeviceId.size < moduleToRun.leafPlatforms.size
                userReadableError {
                    this.append("No platforms of module '${moduleToRun.userReadableName}'")
                    if (platformsAreFiltered) {
                        this.append(" that are runnable on this host")
                    }
                    this.appendLine(" support device selection with --device-id. Please remove the option.")
                    this.appendLine()
                    this.appendLine("Current platforms: ${formatModulePlatforms(moduleToRun)}")
                    if (platformsAreFiltered) {
                        this.appendLine("Runnable on this host: ${formatPlatforms(runnablePlatformsIgnoringDeviceId)}")
                    }
                }
            }
            .filterIf(deviceId == null) { !it.requiresPhysicalDeviceSelection }
            .ifEmpty {
                userReadableError(
                    "Please select a physical device with --device-id to run module " +
                            "'${moduleToRun.userReadableName}'."
                )
            }
        if (effectivelyRunnablePlatforms.size > 1) {
            // special case where there is definitely a preference to avoid Rosetta
            if (effectivelyRunnablePlatforms.toSet() == setOf(Platform.MACOS_X64, Platform.MACOS_ARM64)
                && SystemInfo.CurrentHost.family.isMac
                && SystemInfo.CurrentHost.arch == Arch.Arm64
            ) {
                return Platform.MACOS_ARM64
            }

            // TODO discriminate iosArm64 vs iosSimulatorArm64 based on --device-id if provided?
            //   It feels a bit stupid to ask the user to choose one of these platforms if they specified the exact
            //   device they wanted to run. We could find the type of device it is using xcrun.

            if (!terminal.terminalInfo.interactive) {
                // we still list runnablePlatformsIgnoringDeviceId in the error, because we don't know if the user will
                // add --device-id in the next try
                userReadableError("""
                    Multiple platforms are available to run in module '${moduleToRun.userReadableName}'.
                    Please specify one with '--platform' argument.
    
                    Runnable on this host: ${formatPlatforms(runnablePlatformsIgnoringDeviceId)}
                """.trimIndent())
            }
            return terminal.interactiveSelectList(
                title = "Multiple platforms are available to run in module '${moduleToRun.userReadableName}', please choose:",
                items = effectivelyRunnablePlatforms.toList(),
                nameSelector = { it.pretty },
                filterable = true,
            ) ?: throw PrintMessage("No platform selected, run operation aborted")
        }
        return effectivelyRunnablePlatforms.single()
    }

    companion object {
        const val COMPOSE_HOT_RELOAD_OPTION_NAME = "--compose-hot-reload-mode"
    }
}

private fun AmperModule.canBeRunFromCurrentHost(): Boolean = leafPlatforms.any { it in currentHostRunnablePlatforms }

private fun AmperModule.supportsDeviceIdSelection(): Boolean = leafPlatforms.any { it.supportsDeviceSelection }

private fun AmperModule.requiresPhysicalDeviceToRunFromCurrentHost(): Boolean =
    (leafPlatforms intersect currentHostRunnablePlatforms).all { it.requiresPhysicalDeviceSelection }

private val Platform.supportsDeviceSelection: Boolean
    get() = this == Platform.ANDROID || isDescendantOf(Platform.IOS)

private val Platform.requiresPhysicalDeviceSelection: Boolean
    get() = isAppleDevice
