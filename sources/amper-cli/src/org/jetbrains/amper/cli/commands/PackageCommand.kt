/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.buildTypeOption
import org.jetbrains.amper.cli.options.leafPlatformOption
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.tasks.IpaPackageSettings
import org.jetbrains.amper.tasks.PackageSettings
import org.jetbrains.amper.tasks.ipaAuthKey
import kotlin.io.path.absolute

internal class PackageCommand : AmperModelAwareCommand(name = "package") {

    // TODO: Redesign this command so that the format is a subcommand and all the specific parameters and semantics are inside that subcommand

    private val modules by moduleOption(
        help = "The specific module to package (run the `show modules` command to get the modules list). " +
                "This option can be repeated to package several modules."
    ).multiple().unique()

    private val platforms by leafPlatformOption(
        help = "The target platform to package for. This option can be repeated to package for several platforms.",
    ).multiple().unique()

    private val buildTypes by buildTypeOption(
        help = "The variant to package. This option can be repeated to package several variants. " +
                "The release variant is packaged by default."
    ).multiple().unique()

    private val formats by option(
        "-f",
        "--format",
        help = "The output package format. This option can be repeated to package into several formats.",
    )
        .enum<PackageTask.Format> { it.value }
        .multiple()
        .unique()

    private val ipaExportOptionsPlistPath by option(
        "--ipa-export-options-plist-path",
        help = "Path to an ExportOptions.plist file to use when packaging iOS apps into the `ipa` format. " +
                "Required for manual signing. If omitted for automatic signing, development export options " +
                "are generated from the Xcode project signing settings.",
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true)

    override fun help(context: Context): String = "Package the project artifacts for distribution"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val packageSettings = PackageSettings(
            ipa = IpaPackageSettings(
                exportOptionsPlistPath = ipaExportOptionsPlistPath?.absolute(),
                authKeyProvider = ::ipaAuthKey,
            ),
        )
        withBackend(
            cliContext = cliContext,
            model = model,
            packageSettings = packageSettings,
        ) { backend ->
            backend.`package`(
                platforms = platforms.takeIf { it.isNotEmpty() },
                modules = modules.takeIf { it.isNotEmpty() },
                buildTypes = buildTypes.takeIf { it.isNotEmpty() },
                formats = formats.takeIf { it.isNotEmpty() },
            )
        }
        printSuccessfulCommandConclusion("Build successful")
    }
}