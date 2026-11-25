/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.jetbrains.amper.oldfiles.OldEntry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CleanOldDataUtilsTest {

    @TempDir
    private lateinit var root: Path

    @Test
    fun `the days prompt returns the entered number of days`() {
        val recorder = interactiveRecorder(inputLines = ["60"])
        val terminal = Terminal(terminalInterface = recorder)

        val days = terminal.promptForOldDataDays()

        assertEquals(60, days)
    }

    @Test
    fun `the days prompt returns the default number of days on an empty answer`() {
        val recorder = interactiveRecorder(inputLines = [""])
        val terminal = Terminal(terminalInterface = recorder)

        val days = terminal.promptForOldDataDays(defaultDays = 45)

        assertEquals(45, days)
    }

    @Test
    fun `the days prompt rejects answers that are not whole positive numbers`() {
        val recorder = interactiveRecorder(inputLines = ["forty", "0", "7"])
        val terminal = Terminal(terminalInterface = recorder)

        val days = terminal.promptForOldDataDays()

        assertEquals(7, days)
        assertContains(recorder.output(), "Enter a whole number of days")
        assertContains(recorder.output(), "The number of days must be a positive number")
    }

    @Test
    fun `the old data summary lists the entries with their sizes and stripped names`() {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
        val terminal = Terminal(terminalInterface = recorder)
        val groups = [
            OldDataGroup(
                title = "Project logs",
                entries = [
                    oldEntry(Path("/some/build/logs/amper_2026-01-01_10-00-00_1234-5678_build"), sizeBytes = 1536),
                ],
            ),
            OldDataGroup(title = "Telemetry", entries = []),
        ]

        terminal.printOldDataSummary(days = 30, groups = groups)

        val output = recorder.output()
        assertContains(output, "The data below is older than 30 days:")
        assertContains(output, "  Project logs — 1 entry, 1.5 KiB")
        assertContains(output, "    amper_2026-01-01_10-00-00_1234-5678_build")
        assertContains(output, "  Telemetry — nothing to remove")
        assertContains(output, "Total: 1 entry, 1.5 KiB")
    }

    @Test
    fun `the old data summary reports when there is nothing to remove at all`() {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
        val terminal = Terminal(terminalInterface = recorder)
        val groups = [OldDataGroup(title = "Telemetry", entries = [])]

        terminal.printOldDataSummary(days = 30, groups = groups)

        assertContains(recorder.output(), "No data older than 30 days was found")
    }

    @Test
    fun `the old data summary lists at most a fixed number of entries per group`() {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
        val terminal = Terminal(terminalInterface = recorder)
        val entries = (1..15).map { oldEntry(Path("/some/cache/entry-$it"), sizeBytes = 1) }
        val groups = [OldDataGroup(title = "Downloaded files", entries = entries)]

        terminal.printOldDataSummary(days = 30, groups = groups)

        val output = recorder.output()
        assertContains(output, "  Downloaded files — 15 entries, 15 B")
        assertContains(output, "… and 5 more")
        assertFalse("entry-11" in output, "Only the first 10 entries should be listed")
    }

    @Test
    fun `the removal confirmation accepts the default yes`() {
        val recorder = interactiveRecorder(inputLines = [""])
        val terminal = Terminal(terminalInterface = recorder)

        val confirmed = terminal.confirmOldDataRemoval(totalSizeBytes = 4096)

        assertTrue(confirmed)
        assertContains(recorder.output(), "4.0 KiB")
    }

    @Test
    fun `the removal confirmation declines with no`() {
        val recorder = interactiveRecorder(inputLines = ["n"])
        val terminal = Terminal(terminalInterface = recorder)

        val confirmed = terminal.confirmOldDataRemoval(totalSizeBytes = 4096)

        assertFalse(confirmed)
    }

    @Test
    fun `sizes are user readable`() {
        assertEquals("0 B", 0L.toUserReadableSize())
        assertEquals("512 B", 512L.toUserReadableSize())
        assertEquals("1.5 KiB", 1536L.toUserReadableSize())
        assertEquals("3.0 MiB", (3L * 1024 * 1024).toUserReadableSize())
        assertEquals("2.5 GiB", ((2.5 * 1024).toLong() * 1024 * 1024).toUserReadableSize())
        assertEquals("3.0 TiB", (3L * 1024 * 1024 * 1024 * 1024).toUserReadableSize())
    }

    @Test
    fun `entries containing files that are in use are excluded from removal`() {
        val oldDistribution = oldEntry(Path("/some/cache/cli/kotlin-cli-0.1.0"), sizeBytes = 16)
        val otherOldDistribution = oldEntry(Path("/some/cache/cli/kotlin-cli-0.2.0"), sizeBytes = 16)
        val inUsePaths = [
            Path("/some/cache/cli/kotlin-cli-0.1.0/lib/kotlin-cli.jar"),
            Path("/some/other/dir/some-jdk-home"),
        ]

        val remaining = [oldDistribution, otherOldDistribution].excludingEntriesInUse(inUsePaths)

        assertEquals([Path("/some/cache/cli/kotlin-cli-0.2.0")], remaining.map { it.path })
    }

    @Test
    fun `entries are excluded even when their path goes through a symlink and the in-use path is real`() {
        val realCache = (root / "real-cache").createDirectories()
        val distribution = realCache / "cli" / "kotlin-cli-0.1.0"
        (distribution / "lib" / "kotlin-cli.jar").createParentDirectories().writeText("jar")
        val linkedCache = root / "linked-cache"
        // Creating symbolic links may require elevated privileges on some systems (e.g. Windows).
        assumeTrue(
            runCatching { linkedCache.createSymbolicLinkPointingTo(realCache) }.isSuccess,
            "Symbolic links are not supported on this system",
        )

        // The entry is listed via the symlinked path, while the in-use path is the real path, like the paths of the
        // currently running JVM are.
        val entries = [oldEntry(linkedCache / "cli" / "kotlin-cli-0.1.0", sizeBytes = 16)]
        val inUsePaths = [(distribution / "lib" / "kotlin-cli.jar").toRealPath()]

        val remaining = entries.excludingEntriesInUse(inUsePaths)

        assertTrue(remaining.isEmpty(), "The entry containing the in-use file should be excluded")
    }

    private fun oldEntry(path: Path, sizeBytes: Long): OldEntry = OldEntry(
        path = path,
        sizeBytes = sizeBytes,
        timestamp = Instant.parse("2026-01-10T00:00:00Z"),
    )

    private fun interactiveRecorder(inputLines: List<String> = []): TerminalRecorder = TerminalRecorder(
        ansiLevel = AnsiLevel.NONE,
        outputInteractive = true,
        inputInteractive = true,
    ).apply { this.inputLines.addAll(inputLines) }
}
