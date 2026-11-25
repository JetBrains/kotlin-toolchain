/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.ConversionResult
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.lazyload.distributionRoot
import org.jetbrains.amper.cli.options.ProjectLayoutOptions
import org.jetbrains.amper.cli.terminal.promptBoolean
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import org.jetbrains.amper.oldfiles.OldEntry
import org.jetbrains.amper.oldfiles.OldIndicator
import org.jetbrains.amper.oldfiles.listOldEntries
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private enum class CleanScope(val cleansProject: Boolean, val cleansShared: Boolean) {
    Project(cleansProject = true, cleansShared = false),
    Shared(cleansProject = false, cleansShared = true),
    All(cleansProject = true, cleansShared = true),
}

private class CleanOldDataOptions : OptionGroup("Old data options") {
    @Suppress("unused") // Used to make other options required when appears
    val old by option(
        "--old",
        help = "Remove only the old data (see `--older-than-days`) instead of removing everything",
    )
        // We make it nullable required flag to make cooccurring work correctly.
        .nullableFlag()
        .required()

    val days by option(
        "--older-than-days",
        metavar = "N",
        help = "Consider data old if it is older than this many days (default: `30`). The age of data is determined by its creation time for logs and by its access or modification time for everything else.",
    ).int()

    val dryRun by option(
        "--dry-run",
        help = "Only print the old data that would be removed, without removing anything",
    ).flag()
}

private val LOG = LoggerFactory.getLogger(CleanCommand::class.java)

// Not an AmperProjectAwareCommand because we don't want to generate logs and telemetry in the build folder.
// This is sort of a global command that cleans the project where it's called.
internal class CleanCommand : AmperSubcommand(name = "clean") {

    private val layoutOptions by ProjectLayoutOptions()
    private val cleanScope by mutuallyExclusiveOptions(
        option(
            "--shared",
            help = "Remove the caches that are shared between projects instead of the project's build output",
        ).flag()
            .convert { CleanScope.Shared },
        option(
            "--all",
            help = "Remove both the project's build output and the shared caches",
        ).flag()
            .convert { CleanScope.All },
        name = "Clean scope options",
    )
        .single()
        .default(CleanScope.Project)

    private val cleanOldDataOptions by CleanOldDataOptions().cooccurring()

    override fun help(context: Context): String =
        "Remove the output and caches produced by other commands. By default, affects only the project's cache."

    override suspend fun run() {
        val projectContext: ProjectCliContext? = if (cleanScope.cleansProject)
            findProjectCliContext(failIfNotFound = cleanScope == CleanScope.Project)
        else
            null

        val cleanOldDataOptions = cleanOldDataOptions
        if (cleanOldDataOptions != null) {
            cleanOldData(
                shouldClearProject = cleanScope.cleansProject,
                projectContext = projectContext,
                sharedCaches = cleanScope.cleansShared,
                days = cleanOldDataOptions.days,
                dryRun = cleanOldDataOptions.dryRun,
            )
        } else {
            if (cleanScope.cleansProject) projectContext?.cleanProjectOutput()
            if (cleanScope.cleansShared) cleanSharedCaches()
            printSuccessfulCommandConclusion("Clean successful")
        }
    }

    private suspend fun findProjectCliContext(failIfNotFound: Boolean): ProjectCliContext? {
        val cliContext = findCliContext(layoutOptions)
        return if (cliContext !is ProjectCliContext) {
            if (failIfNotFound) userReadableError("No Kotlin project found, nothing to clean")
            terminal.warning("No Kotlin project found, skipping the project's build output. Use '--shared' to only clean the shared caches")
            null
        } else {
            cliContext
        }
    }

    private suspend fun ProjectCliContext.cleanProjectOutput() {
        val buildOutputRoot = buildOutputRoot.path
        if (buildOutputRoot.exists()) {
            terminal.withIndeterminateProgress("Deleting project build output and caches…") {
                buildOutputRoot.deleteRecursively()
            }
        }
    }

    private suspend fun cleanSharedCaches() {
        val sharedCachesRoot = commonOptions.sharedCachesRoot.path
        if (sharedCachesRoot.exists()) {
            terminal.withIndeterminateProgress("Deleting shared caches at $sharedCachesRoot…") {
                sharedCachesRoot.deleteRecursively()
            }
        }
    }

    private suspend fun cleanOldData(
        shouldClearProject: Boolean,
        projectContext: ProjectCliContext?,
        sharedCaches: Boolean,
        days: Int?,
        dryRun: Boolean,
    ) {
        val daysThreshold = resolveOldDataDays(days) ?: return
        if (daysThreshold < 1) {
            userReadableError("The number of days must be a positive number")
        }
        val cutoff = Clock.System.now() - daysThreshold.days

        val groups = terminal.withIndeterminateProgress("Looking for old data…") {
            buildList {
                if (shouldClearProject && projectContext != null) {
                    addAll(projectContext.projectOldDataGroups(cutoff))
                }
                if (sharedCaches) {
                    addAll(sharedOldDataGroups(cutoff))
                }
            }
        }

        val oldEntriesCount = groups.sumOf { it.entries.size }
        terminal.printOldDataSummary(daysThreshold, groups)

        if (oldEntriesCount == 0) return

        if (dryRun) {
            terminal.println()
            terminal.println("Nothing was removed (dry run)")
            return
        }

        if (terminal.terminalInfo.interactive && !terminal.confirmOldDataRemoval(groups.sumOf { it.totalSizeBytes })) {
            terminal.println()
            terminal.println("Aborted, nothing was removed")
            return
        }

        removeOldEntries(groups)
        printSuccessfulCommandConclusion("Clean successful")
    }

    /**
     * Returns the number of days after which data is considered old, or `null` if the user declined to answer
     * the prompt.
     */
    private fun resolveOldDataDays(explicitDays: Int?): Int? {
        if (explicitDays != null) return explicitDays
        if (!terminal.terminalInfo.interactive) return DEFAULT_OLD_DATA_DAYS
        return terminal.promptForOldDataDays() ?: run {
            terminal.println()
            terminal.println("Aborted, nothing was removed")
            null
        }
    }

    private fun ProjectCliContext.projectOldDataGroups(cutoff: Instant): List<OldDataGroup> {
        return [
            OldDataGroup(
                "Project logs",
                projectLogsRoot.path.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)
            )
        ]
    }

    private fun sharedOldDataGroups(cutoff: Instant): List<OldDataGroup> {
        val userCacheRoot = commonOptions.sharedCachesRoot
        val inUsePaths = currentJvmInUsePaths()

        fun oldEntries(areaRoot: Path): List<OldEntry> =
            areaRoot.listOldEntries(cutoff).excludingEntriesInUse(inUsePaths)

        fun oldCreatedEntries(areaRoot: Path): List<OldEntry> =
            areaRoot.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)
                .excludingEntriesInUse(inUsePaths)

        return [
            OldDataGroup("Telemetry", oldCreatedEntries(userCacheRoot.telemetryRoot)),
            OldDataGroup("Distributions", oldEntries(userCacheRoot.cliBootstrapCache)),
            OldDataGroup("Downloaded files", oldEntries(userCacheRoot.downloadCache)),
            OldDataGroup("Extracted files", oldEntries(userCacheRoot.extractCache)),
        ]
    }

    private suspend fun removeOldEntries(groups: List<OldDataGroup>) {
        for (group in groups) {
            if (group.entries.isEmpty()) continue
            terminal.withIndeterminateProgress("Removing old ${group.title.lowercase()}…") {
                group.entries.forEach { it.path.deleteRecursively() }
            }
        }
    }
}

/**
 * The real paths of the files and directories that the current JVM runs from, like the distribution and the runtime.
 * Entries that contain them are never considered old, so that `clean --old` can't remove the code that is currently
 * executing.
 */
internal fun currentJvmInUsePaths(): List<Path> =
    buildList {
        System.getProperty("java.home")?.let { add(Path(it)) }
        add(distributionRoot)
    }.mapNotNull { rawPath ->
        try {
            rawPath.toRealPath()
        } catch (e: IOException) {
            LOG.debug("Failed to get real path for {}", rawPath, e)
            null
        }
    }.toList()

/**
 * The default number of days after which data is considered old by 'clean --old'.
 */
internal const val DEFAULT_OLD_DATA_DAYS = 30

/**
 * A group of old entries that belong to the same area, e.g. all old logs of a project, with a user-readable title.
 */
internal data class OldDataGroup(
    /**
     * The user-readable title of the group, e.g. "Project logs".
     */
    val title: String,
    val entries: List<OldEntry>,
) {
    val totalSizeBytes: Long
        get() = entries.sumOf { it.sizeBytes }
}

/**
 * Asks the user for the number of days after which data is considered old.
 *
 * Returns `null` if the user canceled the prompt.
 */
internal fun Terminal.promptForOldDataDays(defaultDays: Int = DEFAULT_OLD_DATA_DAYS): Int? = prompt(
    prompt = "Remove the data that is older than how many days?",
    default = defaultDays,
    showChoices = false,
    convert = { input ->
        val days = input.trim().toIntOrNull()
        when {
            days == null -> ConversionResult.Invalid("Enter a whole number of days")
            days < 1 -> ConversionResult.Invalid("The number of days must be a positive number")
            else -> ConversionResult.Valid(days)
        }
    },
)

/**
 * Prints the summary of the old data: what can be removed, and how much space it takes.
 */
internal fun Terminal.printOldDataSummary(days: Int, groups: List<OldDataGroup>) {
    val oldEntriesCount = groups.sumOf { it.entries.size }
    println()
    if (oldEntriesCount == 0) {
        println("No data older than $days days was found")
        return
    }

    val sizeStyle = theme.info + TextStyles.bold.style
    println("The data below is older than $days days:")
    println()
    for (group in groups) {
        if (group.entries.isEmpty()) {
            println("  ${group.title} — ${theme.muted("nothing to remove")}")
            continue
        }
        val entriesText = theme.muted(entriesCountText(group.entries.size))
        val sizeText = sizeStyle(group.totalSizeBytes.toUserReadableSize())
        println("  ${group.title} — $entriesText, $sizeText")

        group.entries.take(MAX_LISTED_OLD_PATHS).forEach {
            println("    ${theme.muted(it.path.name)}")
        }

        val remainingCount = group.entries.size - MAX_LISTED_OLD_PATHS
        if (remainingCount > 0) {
            println("      ${theme.muted("… and $remainingCount more")}")
        }
    }
    println()
    val totalEntriesText = theme.muted(entriesCountText(oldEntriesCount))
    val totalSizeText = sizeStyle(groups.sumOf { it.totalSizeBytes }.toUserReadableSize())
    println("Total: $totalEntriesText, $totalSizeText")
}

/**
 * Asks the user to confirm removing the old data. Returns `false` if the user declined or canceled the prompt.
 */
internal fun Terminal.confirmOldDataRemoval(totalSizeBytes: Long): Boolean =
    promptBoolean("Remove the data above, ${totalSizeBytes.toUserReadableSize()} in total?", default = true) == true

internal fun List<OldEntry>.excludingEntriesInUse(inUsePaths: List<Path>): List<OldEntry> {
    val realInUsePaths = inUsePaths.map { it.toRealPathOrListed() }
    return filter { entry -> realInUsePaths.none { it.startsWith(entry.path.toRealPathOrListed()) } }
}

private fun Path.toRealPathOrListed(): Path = runCatching { toRealPath() }.getOrDefault(this)

internal fun Long.toUserReadableSize(): String {
    var size = toDouble()
    for (unit in ["B", "KiB", "MiB", "GiB"]) {
        if (size < 1024.0) {
            return if (unit == "B") "$this B" else "${String.format(Locale.ROOT, "%.1f", size)} $unit"
        }
        size /= 1024.0
    }
    return "${String.format(Locale.ROOT, "%.1f", size)} TiB"
}

private fun entriesCountText(count: Int): String = "$count ${if (count == 1) "entry" else "entries"}"

private const val MAX_LISTED_OLD_PATHS = 10
