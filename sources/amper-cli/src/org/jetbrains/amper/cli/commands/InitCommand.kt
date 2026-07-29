/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates
import org.jetbrains.amper.templates.TemplateFile
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal class InitCommand : AmperSubcommand(name = "init") {

    private val targetDir by option(
        "--target-dir",
        help = "The directory to create the project in (defaults to the current directory)",
    )
        .path(canBeFile = false, mustExist = false)
        .default(Path(System.getProperty("user.dir")))

    private val template by argument(help = "The name of a project template (leave blank to select interactively from a list)")
        .choice(AmperProjectTemplates.availableTemplates.associateBy { it.id })
        .optional()

    override fun help(context: Context): String = "Initialize a new Kotlin project based on a template"

    override suspend fun run() {
        val selectedTemplate = template ?: promptForTemplate()
        val wrappersGenerated = terminal.withIndeterminateProgress("Extracting template ${terminal.theme.info(selectedTemplate.id)} to ${targetDir}…") {
            selectedTemplate.extractTo(outputDir = targetDir)
            generateWrapperScripts(targetDir)
        }

        printSuccessfulCommandConclusion("Project successfully generated")

        if (wrappersGenerated) {
            terminal.println()
            // On Windows, we don't need the .bat extension when calling the command. Even ./kotlin works in PowerShell.
            // Calling .\kotlin works both in PowerShell and cmd.exe, so we use this on Windows.
            val buildCommand = if (OsFamily.current.isWindows) ".\\kotlin build" else "./kotlin build"
            terminal.println(
                "Now you may build your project with ${terminal.theme.info(buildCommand)} or open this folder in an " +
                        "IDE with the Kotlin Toolchain plugin"
            )
        }
    }

    private fun promptForTemplate(): AmperProjectTemplate = terminal.interactiveSelectList(
        title = "Select a project template:",
        items = AmperProjectTemplates.availableTemplates,
        nameSelector = { terminal.theme.info.invoke(it.name) },
        descriptionSelector = { it.description.prependIndent("  ") },
    ) ?: throw PrintMessage("No template selected, project generation aborted")

    private fun AmperProjectTemplate.extractTo(outputDir: Path) {
        val files = listFiles()
        checkTemplateFilesConflicts(files, outputDir)
        outputDir.createDirectories()
        files.forEach {
            it.extractTo(outputDir)
        }
    }

    private fun generateWrapperScripts(targetRootDir: Path): Boolean {
        val distributionPath = System.getenv("KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR")
        if (distributionPath.isNullOrEmpty()) {
            logger.warn("Kotlin CLI was not run from kotlin wrapper, skipping generating wrappers for $targetRootDir")
            return false
        }
        // Written by `download_and_extract` wrapper routine.
        val sha256 = Path(distributionPath, ".flag").readText().trim()
        AmperWrappers.generate(
            targetDir = targetRootDir,
            amperVersion = AmperBuild.mavenVersion,
            amperDistTgzSha256 = sha256,
        )
        return true
    }

    private fun checkTemplateFilesConflicts(templateFiles: List<TemplateFile>, outputDir: Path) {
        val alreadyExistingFiles = templateFiles
            .map { it.relativePath }
            .filter { outputDir.resolve(it).exists() }

        val pathsBlockingDirectories = templateFiles
            .flatMap { it.relativePath.ancestorRelativePaths() }
            .distinct()
            .filter { outputDir.resolve(it).isRegularFile() }

        if (alreadyExistingFiles.isEmpty() && pathsBlockingDirectories.isEmpty()) return

        userReadableError(buildString {
            appendLine("The following conflicts must be resolved before generating the project:")
            if (alreadyExistingFiles.isNotEmpty()) {
                appendLine()
                appendLine("Files that would be overwritten by the template:")
                alreadyExistingFiles.sorted().forEach { appendLine("  $it") }
            }
            if (pathsBlockingDirectories.isNotEmpty()) {
                appendLine()
                appendLine("Paths that exist as files but are needed as directories:")
                pathsBlockingDirectories.sorted().forEach { appendLine("  ${it.invariantSeparatorsPathString}") }
            }
            appendLine()
            append("Please move, rename, or delete them before running the command again.")
        })
    }
}

private fun String.ancestorRelativePaths(): Sequence<Path> = generateSequence(Path(this).parent) { it.parent }
