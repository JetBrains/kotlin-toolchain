/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.cli.userReadableError
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.Path

internal const val ProjectPathPrompt = "Project path"

internal class NewCommand : AbstractNewProjectCommand(name = "new") {

    private val projectPathArgument by argument(
        name = "project-path",
        help = "The directory to create, as an absolute path or a path relative to the current directory. " +
                "The project name is derived from the last directory component.",
    ).optional()

    override fun help(context: Context): String = "Create a Kotlin project in a new directory"

    override suspend fun resolveTargetDir(): Path {
        val workingDir = Path(System.getProperty("user.dir"))
        val projectPath = projectPathArgument
        if (projectPath == null) {
            if (!terminal.terminalInfo.interactive) {
                userReadableError("Please specify the project path, e.g. `kotlin new my-project`.")
            }
            return terminal.promptForProjectPath(workingDir)
        }
        if (projectPath.isBlank()) userReadableError("Project path must not be blank.")
        try {
            return workingDir.resolve(projectPath)
        } catch (_: InvalidPathException) {
            userReadableError("Project path is not valid on this system.")
        }
    }
}

internal fun Terminal.promptForProjectPath(workingDir: Path): Path {
    while (true) {
        val input = prompt(prompt = ProjectPathPrompt)?.trim()
            ?: throw PrintMessage("Command aborted.")
        if (input.isBlank()) {
            warning("Project path must not be blank. Please try again.")
            continue
        }
        try {
            return workingDir.resolve(input)
        } catch (_: InvalidPathException) {
            warning("Project path is not valid on this system. Please try again.")
        }
    }
}
