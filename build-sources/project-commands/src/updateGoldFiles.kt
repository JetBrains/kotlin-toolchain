/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.walk

@TaskAction(ExecutionAvoidance.Disabled) // we can't track all outputs
fun updateGoldFiles(
    @Input(inferTaskDependency = false) amperRootDir: Path,
    versions: Versions,
) {
    runBlocking {
        syncVersions(amperRootDir, versions)
        AmperGoldUpdater(amperRootDir).updateGoldFiles()
    }
}

private class AmperGoldUpdater(
    val amperRootDir: Path,
    val maxAttempts: Int = 10,
) {
    /**
     * The modules containing tests tagged with `gold-file`.
     *
     * The tag alone is not enough to select them: the `test` command requires an explicit module selection when
     * test filters are used, and fails on modules that have no test matching the filters.
     */
    // TODO remove this once KTC-4234 is implemented (we will be able to rely solely on the gold-file tag)
    private val goldFileTestModules = [
        "schema",
        "dr",
        "amper-cli-test",
        "amper-schema-processing",
        "amper-schema-processor",
    ]

    /**
     * The directories under which the gold files of [goldFileTestModules] (and their `.tmp` counterparts) live.
     */
    private val goldFilesRoots = [
        "sources/frontend/schema",
        "sources/frontend/dr",
        "sources/test-integration/amper-cli-test",
        "sources/extensibility/amper-schema-processing",
        "sources/extensibility/amper-schema-processor",
        // contains the shadow schema sources generated from the extensibility API declarations
        "sources/frontend-api",
    ].map { amperRootDir / it }

    suspend fun updateGoldFiles() {
        println("=== Updating gold files ===")
        repeat(maxAttempts) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            println("Attempt $attemptNumber/$maxAttempts: running gold file tests...")
            if (runGoldFileTests().exitCode.value == 0) {
                println("All gold file tests passed.")
                println()
                return
            }

            val updatedFilesCount = updateTmpFilesUnder(goldFilesRoots)
            if (updatedFilesCount == 0) {
                println("Gold file tests failed, but no .tmp files were found.")
                println("Retrying is pointless here because gold files didn't change, please check the test failure.")
                return
            }
            println("Updated $updatedFilesCount gold file(s).")
            println()
        }

        error("Failed to update gold files after $maxAttempts attempts.")
    }

    private suspend fun runGoldFileTests(): ProcessResult = runAmperCli(
        args = buildList {
            add("test")
            addAll(goldFileTestModules.map { "--include-module=$it" })
            add("--include-tag=gold-file")
        }
    )

    private suspend fun runAmperCli(args: List<String>): ProcessResult {
        val isWindows = System.getProperty("os.name").startsWith("Win", ignoreCase = true)
        val amperScript = amperRootDir.resolve(if (isWindows) "kotlin.bat" else "kotlin")
        return runProcess(
            command = listOf(amperScript.pathString) + args,
            outputMode = ProcessOutputMode.Inherit,
            input = ProcessInput.Inherit,
        )
    }

    private fun updateTmpFilesUnder(roots: List<Path>): Int {
        val tempFiles = roots.flatMap { r ->
            r.walk().filter { it.name.endsWith(".tmp") }
        }
        tempFiles.forEach { tmpResultFile ->
            updateGoldFileFor(tmpResultFile)
        }
        return tempFiles.size
    }

    private fun updateGoldFileFor(tmpResultFile: Path) {
        val realGoldFile = tmpResultFile.resolveSibling(tmpResultFile.name.removeSuffix(".tmp"))
        println("Replacing ${realGoldFile.name} with the contents of ${tmpResultFile.name}")
        tmpResultFile.moveTo(realGoldFile, overwrite = true)
    }
}
