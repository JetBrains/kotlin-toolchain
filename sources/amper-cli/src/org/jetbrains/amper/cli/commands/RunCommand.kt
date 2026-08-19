/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Deferred
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.context.copyWithNewProjectContext
import org.jetbrains.amper.cli.context.findProjectContext
import org.jetbrains.amper.cli.options.UserJvmArgsOption
import org.jetbrains.amper.cli.options.buildTypeOption
import org.jetbrains.amper.cli.options.leafPlatformOption
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.options.userJvmArgsOption
import org.jetbrains.amper.cli.project.preparePluginsAndReadModel
import org.jetbrains.amper.cli.resolveModuleToRun
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.compose.reload.HotReloadDelegate
import org.jetbrains.amper.compose.reload.HotReloadLoop
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.ComposeHotReloadSettings
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

    private val jvmMainClass by option("--main-class", help = "The fully-qualified name of the main class to run. This option is only applicable for JVM applications. By default, the main class is read from the module configuration file, or is determined automatically by convention, searching for a main.kt file.")

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
        if (composeHotReloadMode) {
            // If the configuration doesn't actually support hot-reload,
            // it will be diagnosed and the error will be thrown.
            HotReloadLoop.run(HotReloadDelegateImpl(cliContext))
        } else {
            withBackend(cliContext, cliContext.preparePluginsAndReadModel(), runSettings = allRunSettings()) {
                it.runApplication(moduleName = module, platform = platform, buildType = variant)
            }
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

            HotReloadLoop.State(
                cliContext = cliContext,
                model = model,
                hotApp = model.resolveModuleToRun(
                    moduleName = module,
                    platform = platform,
                    isComposeHotReload = true,
                    hasDeviceId = deviceId != null,
                ),
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
                it.runApplication(moduleToRun = state.hotApp, platform = platform, buildType = variant)
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

    companion object {
        const val COMPOSE_HOT_RELOAD_OPTION_NAME = "--compose-hot-reload-mode"
    }
}
