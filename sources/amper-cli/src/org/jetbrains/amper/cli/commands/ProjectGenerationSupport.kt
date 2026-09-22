/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.terminal.promptBoolean
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.wrapper.AmperWrappers
import org.slf4j.Logger
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeToOrNull

internal const val OverwriteOptionName = "--overwrite"

internal fun determineProjectName(outputDir: Path): String {
    val projectName = outputDir.toAbsolutePath().normalize().fileName?.toString()
    if (projectName.isNullOrBlank()) {
        userReadableError(
            "Cannot determine a project name from target directory '$outputDir'. " +
                    "Choose a named directory."
        )
    }
    return projectName
}

/**
 * Checks that generating files at [relativePaths] into [outputDir] would not overwrite anything unexpectedly, reporting
 * a user-readable error listing all conflicts (and how to resolve them) otherwise.
 *
 * Three kinds of conflicts are detected:
 *  - a generated file already exists (as a file or symbolic link),
 *  - a path needs to be a directory but already exists as a regular file or symbolic link,
 *  - a path needs to be a file (it's a generated file) but already exists as a directory.
 *
 * Shared between the plain template extraction flow and the parameterized Compose Multiplatform flow of
 * `kotlin new`/`kotlin init`.
 *
 * Note on concurrency: this is a best-effort check for the common case (a user pointing the command at a
 * non-empty directory), not a guard against two Kotlin Toolchain processes racing on the same output directory.
 * We deliberately don't take a cross-process lock here: project generation is a one-shot scaffolding command (not a
 * long-running build writing into a shared cache), generating into the *same* directory concurrently is a user
 * error, and the worst case of such a race is a partially-written project that this very check flags on the next
 * run. Adding a lock file would either pollute the generated project or require a separate lock location, which
 * isn't worth the complexity for this command.
 */
internal fun checkTemplateFilesConflicts(relativePaths: List<String>, outputDir: Path) {
    val conflicts = findTemplateFilesConflicts(relativePaths, outputDir)
    if (conflicts.isEmpty) return
    userReadableError(conflicts.describe(includeNonInteractiveHint = true))
}

/**
 * In interactive terminals, detects conflicting paths and asks whether they should be overwritten.
 *
 * Returns whether conflicting paths should be cleared. The caller handles explicit `--overwrite`.
 * Non-interactive terminals return `false`, so the caller can report conflicts via [checkTemplateFilesConflicts].
 */
internal fun Terminal.shouldOverwriteConflictingPaths(
    relativePaths: List<String>,
    outputDir: Path,
): Boolean {
    if (!terminalInfo.interactive) return false

    val conflicts = findTemplateFilesConflicts(relativePaths, outputDir)
    if (conflicts.isEmpty) return false

    println(conflicts.describe(includeNonInteractiveHint = false))
    println()
    val shouldOverwrite = promptBoolean("Overwrite the conflicting files and directories?", default = false)
    if (shouldOverwrite != true) throw PrintMessage("Project generation aborted.")
    return true
}

private data class TemplateFilesConflicts(
    val filesToOverwrite: List<String>,
    val filesBlockingDirectories: List<Path>,
    val directoriesBlockingFiles: List<String>,
) {
    val isEmpty: Boolean
        get() = filesToOverwrite.isEmpty() && filesBlockingDirectories.isEmpty() && directoriesBlockingFiles.isEmpty()

    fun describe(includeNonInteractiveHint: Boolean): String = buildString {
        appendLine("The following conflicts must be resolved before generating the project:")
        if (filesToOverwrite.isNotEmpty()) {
            appendLine()
            appendLine("Files that would be overwritten by the template:")
            filesToOverwrite.sorted().forEach { appendLine("  $it") }
        }
        if (filesBlockingDirectories.isNotEmpty()) {
            appendLine()
            appendLine("Paths that exist as files but are needed as directories:")
            filesBlockingDirectories
                .map { it.invariantSeparatorsPathString }
                .sorted()
                .forEach { appendLine("  $it") }
        }
        if (directoriesBlockingFiles.isNotEmpty()) {
            appendLine()
            appendLine("Paths that exist as directories but are needed as files:")
            directoriesBlockingFiles.sorted().forEach { appendLine("  $it") }
        }
        if (includeNonInteractiveHint) {
            appendLine()
            appendLine("Either move, rename, or delete them, or re-run the command with $OverwriteOptionName to replace")
            append("the conflicting files and directories.")
        }
    }.trimEnd()
}

private fun findTemplateFilesConflicts(relativePaths: List<String>, outputDir: Path): TemplateFilesConflicts =
    TemplateFilesConflicts(
        filesToOverwrite = relativePaths.filter {
            val path = outputDir.resolve(it)
            path.isRegularFile(NOFOLLOW_LINKS) || path.isSymbolicLink()
        },
        filesBlockingDirectories = relativePaths
            .flatMap { it.ancestorRelativePaths() }
            .distinct()
            .filter {
                val path = outputDir.resolve(it)
                path.isRegularFile(NOFOLLOW_LINKS) || path.isSymbolicLink()
            },
        directoriesBlockingFiles = relativePaths.filter { outputDir.resolve(it).isDirectory(NOFOLLOW_LINKS) },
    )

internal fun clearConflictingPaths(relativePaths: List<String>, outputDir: Path) {
    removePathsBlockingDirectories(relativePaths, outputDir)
    removeExistingGeneratedFilePaths(relativePaths, outputDir)
}

private fun removePathsBlockingDirectories(relativePaths: List<String>, outputDir: Path) {
    relativePaths
        .flatMap { it.ancestorRelativePaths() }
        .distinct()
        .sortedBy { it.nameCount }
        .map { outputDir.resolve(it) }
        .forEach { path ->
            if (path.isRegularFile(NOFOLLOW_LINKS) || path.isSymbolicLink()) {
                path.deleteRecursively()
            }
        }
}

private fun removeExistingGeneratedFilePaths(relativePaths: List<String>, outputDir: Path) {
    relativePaths
        .map { outputDir.resolve(it) }
        .filter { it.exists(NOFOLLOW_LINKS) }
        .forEach { it.deleteRecursively() }
}

private fun String.ancestorRelativePaths(): Sequence<Path> = generateSequence(Path(this).parent) { it.parent }

/**
 * Generates the `./kotlin` wrapper scripts in [targetRootDir], if the CLI was itself run from a wrapper.
 *
 * A downloaded distribution gets its checksum from its `.flag` file. A distribution built from sources has no
 * archive checksum, so wrapper generation is skipped in that case.
 *
 * Returns whether the wrapper scripts were actually generated.
 */
internal fun generateWrapperScripts(
    targetRootDir: Path,
    logger: Logger,
    distributionDir: Path? = System.getenv("KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::Path),
): Boolean {
    if (distributionDir == null) {
        logger.warn("Kotlin CLI was not run from kotlin wrapper, skipping generating wrappers for $targetRootDir")
        return false
    }

    val distributionChecksumFile = distributionDir.resolve(".flag")
    if (!distributionChecksumFile.isRegularFile()) {
        logger.warn(
            "Kotlin CLI distribution at $distributionDir has no archive checksum, " +
                    "skipping generating wrappers for $targetRootDir"
        )
        return false
    }

    AmperWrappers.generate(
        targetDir = targetRootDir,
        amperVersion = AmperBuild.mavenVersion,
        amperDistTgzSha256 = distributionChecksumFile.readText().trim(),
    )
    return true
}

/**
 * Prints a single copyable command that enters [outputDir] (unless it is [workingDir]) and builds the generated
 * project.
 */
internal fun Terminal.printProjectGeneratedNextSteps(
    outputDir: Path,
    workingDir: Path,
    osFamily: OsFamily = OsFamily.current,
) {
    val absoluteWorkingDir = workingDir.toAbsolutePath().normalize()
    val absoluteOutputDir = absoluteWorkingDir.resolve(outputDir).normalize()
    val cdPrefix = if (absoluteOutputDir == absoluteWorkingDir) "" else {
        val readablePath = absoluteOutputDir.relativeToOrNull(absoluteWorkingDir) ?: absoluteOutputDir
        "cd ${readablePath.quotedForShell(osFamily)} && "
    }
    println("Now you may build your project with:")
    println("  ${theme.info("${cdPrefix}kotlin build")}")
    println("Or open the generated folder in an IDE with the Kotlin Toolchain plugin.")
}

private fun Path.quotedForShell(osFamily: OsFamily): String {
    val unquotedPathRegex = if (osFamily.isWindows) WindowsUnquotedPathRegex else UnixUnquotedPathRegex
    if (pathString.matches(unquotedPathRegex)) return pathString
    return if (osFamily.isWindows) "\"$pathString\"" else "'${pathString.replace("'", "'\"'\"'")}'"
}

private val UnixUnquotedPathRegex = Regex("""[a-zA-Z0-9_./:-]+""")
private val WindowsUnquotedPathRegex = Regex("""[a-zA-Z0-9_./\\:-]+""")
