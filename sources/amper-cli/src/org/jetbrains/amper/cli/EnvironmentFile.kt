/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.intellij.CommandLineUtils
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

private const val environmentFileOption = "--env-file"
private const val wrapperPathEnvironmentVariable = "KOTLIN_CLI_WRAPPER_PATH"
private val environmentVariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal data class EnvironmentFileArguments(
    val files: List<Path>,
    val forwardedArguments: List<String>,
)

internal suspend fun restartWithEnvironmentFilesIfRequested(args: Array<String>): Int? {
    val parsedArguments = parseEnvironmentFileArguments(args)
    if (parsedArguments.files.isEmpty()) {
        return null
    }

    val wrapperPath = System.getenv(wrapperPathEnvironmentVariable)
        ?.takeIf(String::isNotBlank)
        ?: userReadableError("Missing $wrapperPathEnvironmentVariable. Is your Kotlin Toolchain distribution intact?")
    val environment = loadEnvironmentFiles(parsedArguments.files)
    val command = CommandLineUtils.quoteCommandLineForCurrentPlatform(
        listOf(wrapperPath) + parsedArguments.forwardedArguments
    )
    val result = runProcess(
        command = command,
        environment = environment,
        outputMode = ProcessOutputMode.Inherit,
    )
    return result.exitCode
}

internal fun parseEnvironmentFileArguments(args: Array<String>): EnvironmentFileArguments {
    val files = mutableListOf<Path>()
    val forwardedArguments = mutableListOf<String>()
    var index = 0
    var optionsEnded = false

    while (index < args.size) {
        val argument = args[index]
        if (optionsEnded) {
            forwardedArguments += argument
            index++
            continue
        }

        when {
            argument == "--" -> {
                optionsEnded = true
                forwardedArguments += argument
            }
            argument == environmentFileOption -> {
                val file = args.getOrNull(index + 1)
                    ?: userReadableError("Missing path after $environmentFileOption")
                files.add(environmentFilePath(file))
                index++
            }
            argument.startsWith("$environmentFileOption=") -> {
                files.add(environmentFilePath(argument.substringAfter('=')))
            }
            else -> forwardedArguments += argument
        }
        index++
    }
    return EnvironmentFileArguments(files, forwardedArguments)
}

internal fun loadEnvironmentFiles(
    files: List<Path>,
    inheritedEnvironment: Map<String, String> = System.getenv(),
): Map<String, String> {
    return buildMap {
        files.forEach { file ->
            val normalizedFile = file.toAbsolutePath().normalize()
            if (!normalizedFile.isRegularFile()) {
                userReadableError("Environment file does not exist or is not a regular file: $normalizedFile")
            }
            normalizedFile.readLines().forEachIndexed { index, line ->
                val entry = parseEnvironmentFileLine(line, normalizedFile, index + 1) ?: return@forEachIndexed
                if (entry.first !in inheritedEnvironment) {
                    put(entry.first, entry.second)
                }
            }
        }
    }
}

private fun environmentFilePath(value: String): Path {
    if (value.isBlank()) {
        userReadableError("Environment file path cannot be empty")
    }
    return try {
        Path.of(value)
    } catch (e: RuntimeException) {
        userReadableError("Invalid environment file path: $value", cause = e)
    }
}

private fun parseEnvironmentFileLine(line: String, file: Path, lineNumber: Int): Pair<String, String>? {
    val declaration = line.removePrefix("\uFEFF").trim()
    if (declaration.isEmpty() || declaration.startsWith('#')) {
        return null
    }

    val separatorIndex = declaration.indexOf('=')
    if (separatorIndex <= 0) {
        invalidEnvironmentFileLine(file, lineNumber)
    }
    val name = declaration.substring(0, separatorIndex).trim()
    if (!environmentVariableName.matches(name)) {
        invalidEnvironmentFileLine(file, lineNumber)
    }
    val rawValue = declaration.substring(separatorIndex + 1).trim()
    val value = unquoteEnvironmentValue(rawValue, file, lineNumber)
    return name to value
}

private fun unquoteEnvironmentValue(value: String, file: Path, lineNumber: Int): String {
    val quote = value.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return value
    if (value.length < 2 || value.last() != quote) {
        userReadableError("Unclosed quoted value in environment file $file at line $lineNumber")
    }
    return value.substring(1, value.lastIndex)
}

private fun invalidEnvironmentFileLine(file: Path, lineNumber: Int): Nothing =
    userReadableError("Invalid environment variable declaration in $file at line $lineNumber")
