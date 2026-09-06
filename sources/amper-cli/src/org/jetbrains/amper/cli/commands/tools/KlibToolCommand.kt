/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands.tools

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.cli.commands.AmperSubcommand
import org.jetbrains.amper.cli.context.GlobalCliContext
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.project.preparePluginsAndReadModel
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.frontend.kotlinVersion
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.DiscouragedDirectDefaultVersionAccess
import org.jetbrains.amper.intellij.CommandLineUtils
import org.jetbrains.amper.jvm.getDefaultJdk
import org.jetbrains.amper.kotlin.native.downloadAndExtractKotlinNative
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.system.info.OsFamily
import kotlin.io.path.div
import kotlin.io.path.isExecutable
import kotlin.io.path.pathString

internal class KlibToolCommand : AmperSubcommand(name = "klib") {

    private val explicitKotlinVersion by option(
        "--kotlin-version",
        help = "The version of the Kotlin/Native distribution to take the `klib` tool from. " +
                "By default, the Kotlin version is taken from the current project, or is the toolchain's default if " +
                "the command is run outside of a project. If a project uses different Kotlin versions in different " +
                "modules, the highest version is picked.",
    )

    private val klibArguments by argument(name = "klib_arguments").multiple()

    override fun help(context: Context): String = "Run the `klib` tool from an auto-provisioned Kotlin/Native distribution"

    override fun helpEpilog(context: Context): String =
        "Use `--` to separate `klib`'s arguments from the Kotlin CLI options"

    override suspend fun run() {
        val cliContext = findCliContext()

        @OptIn(DiscouragedDirectDefaultVersionAccess::class)
        val kotlinVersion = explicitKotlinVersion ?: when (cliContext) {
            is GlobalCliContext -> DefaultVersions.kotlin
            is ProjectCliContext -> {
                setProjectSpecificState(cliContext)
                val model = cliContext.preparePluginsAndReadModel()
                model.modules.maxOf { ComparableVersion(it.kotlinVersion) }.canonical
            }
        }

        val konanDistribution = Downloader.downloadAndExtractKotlinNative(kotlinVersion, cliContext.userCacheRoot)
            ?: userReadableError("The Kotlin/Native distribution is not available for the current platform")

        val ext = if (OsFamily.current.isWindows) ".bat" else ""
        val klibExecutable = konanDistribution.homeDir / "bin" / "klib$ext"
        if (!klibExecutable.isExecutable()) {
            userReadableError("The klib tool is not present or not executable: $klibExecutable")
        }

        val jdk = context(cliContext.problemReporter) {
            // we always use the default JDK to run the Kotln/Native compiler
            cliContext.jdkProvider.getDefaultJdk()
        }

        val cmd = listOf(klibExecutable.pathString) + klibArguments
        val result = runProcess(
            command = CommandLineUtils.quoteCommandLineForCurrentPlatform(cmd),
            // The klib tool is a script that needs a JVM to run. We don't want to rely on the ambient JAVA_HOME
            // (which may be unset or point to an unsuitable JDK), so we use the JDK provisioned by the toolchain.
            environment = mapOf("JAVA_HOME" to jdk.homeDir.pathString),
            outputMode = ProcessOutputMode.Inherit,
            input = ProcessInput.Inherit,
        )
        if (result.exitCode != 0) {
            userReadableError("klib exited with exit code ${result.exitCode}", exitCode = result.exitCode)
        }
    }
}
