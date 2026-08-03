/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

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
    private val schemaModuleDir = amperRootDir / "sources/frontend/schema"
    private val testResourcesDir = schemaModuleDir / "testResources"
    private val testResourcePathRegex = Regex("(${Regex.escape(testResourcesDir.absolutePathString())})[^),\"'\n\r]*")

    suspend fun updateGoldFiles() {
        withContext(Dispatchers.IO) {
            launch { updateDrGoldFiles(amperRootDir) }
            launch { updateSchemaGoldFiles(amperRootDir) }
            launch { updateCliGoldFiles(amperRootDir) }
            launch { updatePluginGoldFiles(amperRootDir) }
        }
    }

    private suspend fun updateDrGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "dr module",
            goldFilesRoots = listOf(amperRootDir / "sources/frontend/dr"),
        ) {
            runAmperCli(amperRootDir, "test", "-m", "dr")
        }
    }

    private suspend fun updateSchemaGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "schema module",
            goldFilesRoots = listOf(schemaModuleDir),
        ) {
            runAmperCli(amperRootDir, "test", "-m", "schema")
        }
    }

    private suspend fun updateCliGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "amper-cli-test module",
            goldFilesRoots = listOf(amperRootDir / "sources/test-integration/amper-cli-test"),
        ) {
            runAmperCli(amperRootDir, "test", "-m", "amper-cli-test", "--include-classes=*.ShowSettingsCommandTest", "--include-classes=*.ShowDependenciesCommandTest")
        }
    }

    private suspend fun updatePluginGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "amper-cli-test module",
            goldFilesRoots = listOf(
                amperRootDir / "sources/extensibility/amper-schema-processing",
                amperRootDir / "sources/frontend-api",
            ),
        ) {
            runAmperCli(amperRootDir, "test", "-m", "amper-schema-processing")
        }
    }

    private inline fun updateGoldFilesUntilSuccess(sectionName: String, goldFilesRoots: List<Path>, runTests: () -> ProcessResult) {
        println("=== Updating $sectionName gold files ===")
        repeat(maxAttempts) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            println("Attempt $attemptNumber/$maxAttempts: running tests...")
            val exitCode = runTests().exitCode
            if (exitCode == 0) {
                println("Tests passed for $sectionName.")
                println()
                return
            }

            val updatedFilesCount = updateTmpFilesUnder(goldFilesRoots)
            if (updatedFilesCount == 0) {
                println("Tests failed for $sectionName, but no .tmp files were found.")
                println("Retrying is pointless here because gold files didn't change, please check the test failure.")
                return
            } else {
                println("Updated $updatedFilesCount gold file(s) for $sectionName.")
            }
            println()
        }

        error("Failed to update $sectionName gold files after $maxAttempts attempts.")
    }

    private fun updateTmpFilesUnder(roots: List<Path>): Int {
        var updatedFilesCount = 0
        roots
            .flatMap {
                it.walk().filter { it.name.endsWith(".tmp") }
            }
            .forEach { tmpResultFile ->
                updateGoldFileFor(tmpResultFile)
                updatedFilesCount++
            }
        return updatedFilesCount
    }

    private suspend fun runAmperCli(amperRootDir: Path, vararg args: String): ProcessResult {
        val isWindows = System.getProperty("os.name").startsWith("Win", ignoreCase = true)
        val amperScript = amperRootDir.resolve(if (isWindows) "kotlin.bat" else "kotlin")
        return runProcess(
            command = listOf(amperScript.pathString) + args,
            outputMode = ProcessOutputMode.Inherit,
            input = ProcessInput.Inherit,
        )
    }

    private fun updateGoldFileFor(tmpResultFile: Path) {
        val realGoldFile = goldFileFor(tmpResultFile)
        println("Replacing ${realGoldFile.name} with the contents of ${tmpResultFile.name}")
        val newGoldContent = tmpResultFile.contentsWithVariables()
        realGoldFile.writeText(newGoldContent)
        tmpResultFile.deleteExisting()
    }

    private fun goldFileFor(tmpResultFile: Path): Path = tmpResultFile.resolveSibling(tmpResultFile.name.removeSuffix(".tmp"))

    /**
     * Gets the contents of this temp file with the paths replaced with variables, as they are usually in gold files to
     * make them machine-/os-independent.
     */
    private fun Path.contentsWithVariables(): String = readText().replace(testResourcePathRegex) { match ->
        // See variable substitution in schema/helper/util.kt
        match.value
            // {{ testResources }} is used for the "base" path, which is the dir containing the gold file
            .replace(parent.absolutePathString(), "{{ testResources }}")
            // {{ testProcessDir }} is the dir in which tests are run, which is the schema module
            .replace(schemaModuleDir.absolutePathString(), "{{ testProcessDir }}")
            .replace(File.separator, "{{ fileSeparator }}")
    }
}
