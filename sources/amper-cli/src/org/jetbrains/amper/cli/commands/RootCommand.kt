/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.SuspendingCompletionCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.warning
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.datetime.LocalDateTime
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.AmperHelpFormatter
import org.jetbrains.amper.cli.AmperVersion
import org.jetbrains.amper.cli.commands.ide.IdeIntegrationCommand
import org.jetbrains.amper.cli.commands.show.ShowCommand
import org.jetbrains.amper.cli.commands.tools.ToolCommand
import org.jetbrains.amper.cli.logging.LoggingInitializer
import org.jetbrains.amper.cli.options.choiceWithTypoSuggestion
import org.jetbrains.amper.cli.profiling.Profiler
import org.jetbrains.amper.cli.telemetry.TelemetryEnvironment
import org.jetbrains.amper.cli.terminal.createMordantTerminal
import org.jetbrains.amper.cli.unwrap
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withShowCommandSuggestions
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import org.jetbrains.amper.util.DateTimeFormatForFilenames
import org.jetbrains.amper.util.nowInDefaultTimezone
import org.tinylog.Level
import java.io.PrintStream
import kotlin.io.path.Path

internal class RootCommand : SuspendingCliktCommand(name = "kotlin") {

    init {
        versionOption(
            version = AmperBuild.mavenVersion,
            names = ["--version", "-v"],
            message = { AmperVersion.banner },
        )
        eagerOption("-repl", "-Xrepl", hidden = true) {
            userReadableError("The -repl/-Xrepl option is not supported in the Kotlin Toolchain. " +
                    "Use `kotlinc` directly if you need the REPL: `kotlinc -Xrepl`.")
        }
        subcommands(
            BuildCommand(),
            CheckCommand(),
            CleanCommand(),
            CleanSharedCachesCommand(),
            ComposeHotReloadMcpServerCommand(),
            DoCustomCommand(),
            SuspendingCompletionCommand(
                help = "Generate a tab-completion script for the Kotlin CLI for the given shell (bash, zsh, or fish)",
            ),
            IdeIntegrationCommand(),
            InitCommand(),
            PackageCommand(),
            PublishCommand(),
            RunCommand(),
            ShowCommand(),
            TaskCommand(),
            TestCommand(),
            ToolCommand(),
            UpdateCommand(),
            IntegrateLinkagePackageCommand(),
        )
        context {
            // one would be created by default, but we manually set it to customize the theme
            terminal = createMordantTerminal()
            helpFormatter = { context -> AmperHelpFormatter(context) }
            suggestTypoCorrection = suggestTypoCorrection.withShowCommandSuggestions()
        }
    }

    private val consoleLogLevel by option(
        "--log-level",
        help = "Console logging level"
    ).choiceWithTypoSuggestion(
        mapOf(
            "debug" to Level.DEBUG,
            "info" to Level.INFO,
            "warn" to Level.WARN,
            "error" to Level.ERROR,
            "off" to Level.OFF,
        ), ignoreCase = true
    ).default(Level.INFO)

    private val sharedCacheDir by option(
        "--shared-cache-dir",
        help = "Path to the cache directory shared between all Kotlin projects",
    )
        .path(canBeFile = false)
        .convert { AmperUserCacheRoot(it.toAbsolutePath()) }
        // It's ok to use a non-lazy default here because most of the time we'll use the default value anyway.
        // Detecting this path eagerly allows showing the default value in the help.
        .default(AmperUserCacheRoot.fromCurrentUserResult().unwrap())

    private val debuggingOptions by DebuggingOptions()

    override suspend fun run() {

        // Ensure we're writing traces to the configured user cache (we start with the default in early telemetry).
        // For commands that have a project context, the traces will eventually be moved to the project build logs dir.
        TelemetryEnvironment.setUserCacheRoot(sharedCacheDir)

        currentContext.obj = CommonOptions(
            consoleLogLevel = consoleLogLevel,
            sharedCachesRoot = sharedCacheDir,
        )

        if (debuggingOptions.profilerEnabled) {
            spanBuilder("Setup profiler").use {
                Profiler.start(
                    userCacheRoot = sharedCacheDir,
                    snapshotFile = debuggingOptions.profilerSnapshotPath,
                )
            }
        }

        spanBuilder("Setup console logging").use {
            LoggingInitializer.setupConsoleLogging(consoleLogLevel = consoleLogLevel, terminal = terminal)
        }

        fixSystemOutEncodingOnWindows()

        if (debuggingOptions.coroutinesDebugEnabled) {
            if (isWindowsArm64()) {
                // Always fails on Windows Arm64 because ByteBuddy doesn't support it:
                // https://github.com/raphw/byte-buddy/issues/1336
                terminal.warning("Coroutines debug probes are not supported on Windows Arm64")
            } else {
                installCoroutinesDebugProbes()
            }
        }
    }

    data class CommonOptions(
        val consoleLogLevel: Level,
        val sharedCachesRoot: AmperUserCacheRoot,
    )

    /**
     * Some Windows encoding used by default doesn't support symbols used in `show dependencies` output
     * Updating it to UTF-8 solves the issue.
     *
     * See https://github.com/ajalt/mordant/issues/249 for details.
     */
    private fun fixSystemOutEncodingOnWindows() {
        if (!isWindows()) return
        if (System.out.charset() == Charsets.UTF_8) return

        spanBuilder("Fix stdout encoding").useWithoutCoroutines {
            // Set the console code page to 65001 = UTF-8
            val success = Kernel32.INSTANCE.SetConsoleOutputCP(65001)
            if (success) {
                // Replace System.out and System.err with PrintStreams using UTF-8
                System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
                System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
            } else {
                terminal.warning("Failed to set UTF-8 as console output encoding: ${Kernel32Util.getLastErrorMessage()}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun installCoroutinesDebugProbes() {
        spanBuilder("Install coroutines debug probes").useWithoutCoroutines {
            // coroutines debug probes, required to dump coroutines
            try {
                DebugProbes.install()
            } catch (e: Throwable) {
                terminal.warning("Failed to install coroutines debug probes: $e")
            }
        }
    }

    private fun isWindowsArm64(): Boolean = isWindows() && System.getProperty("os.arch") == "aarch64"

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}

private class DebuggingOptions : OptionGroup(name = "Debugging options") {
    val profilerEnabled by option(
        "--profile",
        help = "Profile the Kotlin CLI with the [Async Profiler](https://github.com/async-profiler/async-profiler). " +
                "The path to the snapshot file is determined by `--profiler-snapshot-path`."
    ).flag(default = false)

    val profilerSnapshotPath by option(
        "--profiler-snapshot-path",
        help = "The output path for the snapshot file generated by the profiler."
    )
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .defaultLazy(defaultForHelp = "./async-profiler-snapshot-<datetime>-<pid>.jfr") {
            Path("async-profiler-snapshot-" +
                    "${DateTimeFormatForFilenames.format(LocalDateTime.nowInDefaultTimezone())}-" +
                    "${ProcessHandle.current().pid()}.jfr")
        }

    val coroutinesDebugEnabled by option(
        "--coroutines-debug",
        help = "Enable coroutines debug probes. This allows to dump the running coroutines in case of deadlock.",
    ).flag(default = false)
}
