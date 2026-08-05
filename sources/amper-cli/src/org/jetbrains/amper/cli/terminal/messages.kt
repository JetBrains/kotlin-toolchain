/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.terminal

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RepositoryModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

// TODO this requires a terminal in the compilation tasks, which is not ideal. We should move to a different approach
//   to report CLI-agnostic structured events from tasks and format them in the CLI module.
/**
 * Prints a nicely formatted compilation success message to the terminal, and logs a plain version of this message to
 * the info logs file.
 */
internal fun Terminal.printCompilationSuccess(module: AmperModule, platform: Platform, isTest: Boolean) {
    val themedModuleName = formatModuleName(module)
    val themedPlatformTest = theme.muted("[${platform.schemaValue}${if (isTest) " tests" else ""}]")
    printCompletedMilestone("Compilation successful for $themedModuleName $themedPlatformTest")
}

/**
 * Prints a nicely formatted publication success message to the terminal, and logs a plain version of this message to
 * the info logs file.
 */
internal fun Terminal.printSuccessfulPublicationToMavenLocal(module: AmperModule) {
    val themedModuleName = formatModuleName(module)
    printCompletedMilestone("Module $themedModuleName published to Maven local")
}

/**
 * Prints a nicely formatted publication success message to the terminal, and logs a plain version of this message to
 * the info logs file.
 */
internal fun Terminal.printSuccessfulPublicationToRemoteMaven(
    module: AmperModule,
    repository: RepositoryModel.Publish,
) {
    val themedModuleName = formatModuleName(module)
    val repositoryId = theme.success(repository.id)
    printCompletedMilestone("Module $themedModuleName published to Maven repository $repositoryId")
}

/**
 * Formats the given [module]'s name for presentation in messages.
 */
private fun Terminal.formatModuleName(module: AmperModule): String = if (terminalInfo.ansiLevel > AnsiLevel.NONE) {
    theme.info(module.userReadableName)
} else {
    "'${module.userReadableName}'"
}

/**
 * Prints a message with a green checkmark to the terminal (without terminal logging), and logs the message at info
 * level to the log file.
 */
internal fun Terminal.printCompletedMilestone(message: String) {
    val messageWithCheck = buildString {
        append(theme.success("✓ "))
        append(message)
    }
    printAndLog(terminalMessage = messageWithCheck, logMessage = message.filterAnsiCodes())
}

private fun Terminal.printAndLog(
    terminalMessage: Any,
    logMessage: String,
    logLevel: Level = Level.INFO,
    logger: Logger = LoggerFactory.getLogger("Events"),
) {
    println(terminalMessage)
    withoutConsoleLogging {
        logger.atLevel(logLevel).log(logMessage)
    }
}
