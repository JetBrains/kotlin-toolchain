/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.collectBuildProblems
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters.ModuleDependencyWithOverriddenVersion
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Ignore
import kotlin.test.assertTrue
import kotlin.test.fail

private val logger = LoggerFactory.getLogger(AmperProjectDependenciesTest::class.java)

@Tag("gold-file")
class AmperProjectDependenciesTest: AbstractDependencyInsightsTest() {

    override val testGoldenFilesRoot: Path get() = super.testGoldenFilesRoot.resolve("amper")

    /**
     * This test checks that in full mode, all pathes to the required dependency are shown.
     */
    @Test
    @Ignore("This test works quite long, it resolves dependencies for a test Amper project." +
             "It might be used to check how DR works on Amper project locally.")
    fun `test amper-jic-runner dependency insights`(testInfo: TestInfo) = runModuleDependenciesTest {
        // Uncomment [actualizeAmperProject] if you'd like to update test Amper project to the state of the actual Amper project
        // actualizeAmperProject()

        val aom = getTestProjectModel("amper", testDataRoot)

        val amperJicRunnerGraph = doTestByFile(
            testInfo,
            aom,
            module = "amper-jic-runner",
        )

        assertInsightByFile(
            group = "org.jetbrains.kotlinx",
            module = "kotlinx-serialization-json",
            graph = amperJicRunnerGraph,
            testInfo = testInfo,
        )
    }

    /**
     * AMPER-4882 revealed that the dependency insights graph is calculated for the same coordinates as many times as
     * coordinates are mentioned in the AOM
     * (i.e., the number of times dependency is added to project modules multiplied by the number
     * of resolution contexts (platform and scope)).
     *
     * This test checks that the dependency insights graph is calculated the same number of times as the quantity of
     * different coordinates with an overridden version
     * (one coordinates with an overridden version => one dependency insights graph)
     */
    @Test
    @Ignore("This test works quite long, it resolves dependencies for a test Amper project." +
            "It might be used to check how DR works on Amper project locally.")
    fun `dependency insights in amper`(testInfo: TestInfo) =
        runSlowModuleDependenciesTest {
            // Uncomment [actualizeAmperProject] if you'd like to update test Amper project to the state of the actual Amper project
            // actualizeAmperProject()

            val aom = getTestProjectModel("amper", testDataRoot)

            val projectDeps = doTestByFile(
                testInfo,
                aom,
                ideSyncTestResolutionInput,
                messagesCheck = { node ->
                    assertTrue {
                        node.messages.all { it.severity <= Severity.WARNING }
                    }
                },
                filter = ideSyncModuleResolutionFilter
            )

            assertFiles(testInfo,projectDeps)

            val diagnosticsReporter = CollectingProblemReporter()
            collectBuildProblems(projectDeps, diagnosticsReporter, Level.Warning)
            val buildProblems = diagnosticsReporter.problems

            /**
             * This magic number doesn't matter on its own.
             * What matters is that all warnings are related to overridden dependencies (next check).
             */
            kotlin.test.assertEquals(28, buildProblems.size)

            val overriddenDependencyProblems = buildProblems.filterIsInstance<ModuleDependencyWithOverriddenVersion>()
            kotlin.test.assertEquals(buildProblems.size, overriddenDependencyProblems.size)

            val problematicDependencies = overriddenDependencyProblems.map { it.dependencyNode.key }.distinct()
            kotlin.test.assertEquals(
                setOf(
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-debug",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                    "org.jetbrains.kotlinx:kotlinx-serialization-json",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-test"
                ),
                problematicDependencies.map { it.name }.toSet()
            )

            val uniqueInsights = overriddenDependencyProblems.map { it.overrideInsight }.distinct()
            kotlin.test.assertEquals(
                14, uniqueInsights.size,
                "Insights were calculated unexpected number of times, " +
                        "while calculation of the single insight pr module" +
                        "for the library 'org.jetbrains.compose.runtime:runtime' is expected"
            )
        }


    companion object {
        val allowedExtensions = setOf("yaml", "toml")

        private val projectRoot = Dirs.amperCheckoutRoot

        private val excludedPaths: Set<Path> = setOf(
            projectRoot.resolve("sources\\test-integration\\test-projects")
        )

        fun actualizeAmperProject() {
            val destinationRoot = projectRoot.resolve("sources\\frontend\\dr\\testData\\projects\\amper")
            destinationRoot.deleteRecursively()
            destinationRoot.createDirectories()

            copyFile(projectRoot.resolve("project.yaml"), destinationRoot.resolve("project.yaml"))
            copyFile(projectRoot.resolve("libs.versions.toml"), destinationRoot.resolve("libs.versions.toml"))

            val directoriesToCopy = listOf("build-sources", "sources")
            val allCopiedYamlFiles = mutableSetOf<Path>() // To store all .yaml files copied

            for (dirName in directoriesToCopy) {
                val sourceDir = projectRoot.resolve(dirName)
                if (!sourceDir.exists() || !Files.isDirectory(sourceDir)) {
                    logger.info("Source directory $sourceDir does not exist or is not a directory. Skipping.")
                    continue
                }
                processDirectory(sourceDir, destinationRoot.resolve(dirName), Path(""), allCopiedYamlFiles)
            }

            // --- Renaming and String Replacement Logic ---
            renameTemplates(allCopiedYamlFiles)
        }

        fun containsRelevantFiles(directory: Path): Boolean {
            if (!Files.isDirectory(directory)) return false
            return directory.listDirectoryEntries().any { it.isRegularFile() && allowedExtensions.contains(it.extension) }
        }

        fun processDirectory(sourceDir: Path, destinationRoot: Path, relativePathToRoot: Path, allCopiedYamlFiles: MutableSet<Path>) {
            if (!Files.isDirectory(sourceDir)) return

            if (sourceDir in excludedPaths) return

            val currentDestinationDir = destinationRoot.resolve(relativePathToRoot)
            var containsModuleYaml = false

            // Check if current directory contains relevant files
            if (containsRelevantFiles(sourceDir)) {
                sourceDir.listDirectoryEntries()
                    .filter { it.isRegularFile() && allowedExtensions.contains(it.extension) }
                    .forEach { sourceFile ->
                        val destinationFile = currentDestinationDir.resolve(sourceFile.fileName)
                        currentDestinationDir.createDirectories()
                        copyFile(sourceFile, destinationFile)

                        if (sourceFile.extension == "yaml") {
                            allCopiedYamlFiles.add(destinationFile)
                        }
                        containsModuleYaml = sourceFile.name == "module.yaml"
                    }
            }

            // If no relevant files were processed directly in this directory, then recurse into subdirectories.
            // Otherwise, if files were processed, we do NOT recurse into nested directories.
            if (!containsModuleYaml) {
                sourceDir.listDirectoryEntries()
                    .filter { it.isDirectory() }
                    .forEach { subDir ->
                        processDirectory(subDir, destinationRoot, relativePathToRoot.resolve(subDir.fileName), allCopiedYamlFiles)
                    }
            }
        }

        private fun copyFile(sourceFile: Path, destinationFile: Path) {
            Files.copy(sourceFile, destinationFile, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Copied: $sourceFile to $destinationFile")
        }

        private fun renameTemplates(allCopiedYamlFiles: MutableSet<Path>) {
            // oldName -> newName (e.g., "original.yaml" -> "dr.amper.original.yaml")
            val renamedFilesMap = mutableMapOf<String, String>()
            val filesToProcessForRename = mutableListOf<Path>()

            // Identify files to rename
            for (copiedFile in allCopiedYamlFiles) {
                val fileName = copiedFile.fileName.toString()
                if (fileName.endsWith(".module-template.yaml") || fileName.endsWith("-rules.yaml")) {
                    filesToProcessForRename.add(copiedFile)
                }
            }

            // Perform renaming
            for (fileToRename in filesToProcessForRename) {
                val originalFileName = fileToRename.fileName.toString()
                val newFileName = "dr.amper.${originalFileName}"
                val newFilePath = fileToRename.resolveSibling(newFileName)
                try {
                    Files.move(fileToRename, newFilePath, StandardCopyOption.REPLACE_EXISTING)
                    logger.info("Renamed: $originalFileName to $newFileName")
                    renamedFilesMap[originalFileName] = newFileName
                    // Update allCopiedYamlFiles as the path has changed for this file
                    allCopiedYamlFiles.remove(fileToRename)
                    allCopiedYamlFiles.add(newFilePath)
                } catch (e: Exception) {
                    fail("Error renaming file $originalFileName: ${e.message}")
                }
            }

            // Update string occurrences in all copied YAML files
            if (renamedFilesMap.isNotEmpty()) {
                for (yamlFile in allCopiedYamlFiles) {
                    if (yamlFile.extension == "yaml") { // Double-check it's a YAML file
                        var content = Files.readString(yamlFile)
                        var changed = false

                        for ([oldName, newName] in renamedFilesMap) {
                            // Replace the full filename including extension
                            val newContent = content.replace(oldName, newName)
                            if (newContent != content) {
                                content = newContent
                                changed = true
                            }
                        }

                        if (changed) {
                            Files.writeString(yamlFile, content)
                            logger.info("Updated content in: $yamlFile")
                        }
                    }
                }
            }
        }
    }
}