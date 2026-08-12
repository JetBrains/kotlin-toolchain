/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.kotlinModuleName
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.extract.extractZip
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.compose.MergedPreparedComposeResourcesDirArtifact
import org.jetbrains.amper.tasks.web.NpmInstallTask
import org.jetbrains.amper.tasks.web.NpmInstallTask.Companion.json
import org.jetbrains.amper.tasks.web.VENDORS
import org.jetbrains.amper.tasks.web.WebLinkTask
import org.jetbrains.amper.tasks.web.generateImportMap
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class WasmJsBuildTask(
    override val platform: Platform,
    override val module: AmperModule,
    override val buildType: BuildType,
    private val taskOutputPath: TaskOutputRoot,
    override val taskName: TaskName,
    private val tempRoot: AmperProjectTempRoot,
    private val incrementalCache: IncrementalCache,
    userCacheRoot: AmperUserCacheRoot,
) : ArtifactTaskBase(), BuildTask {
    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(Platform.WASM_JS))
    }

    override val isTest: Boolean
        get() = false

    /**
     * Compose resources of this module and of all its module dependencies, already laid out under their
     * `composeResources/<package>/` packaging dirs. They have to be served next to `index.html`, because this is
     * where the generated accessors expect to find them at runtime.
     */
    private val composeResources by Selectors.fromModuleWithDependencies(
        type = MergedPreparedComposeResourcesDirArtifact::class,
        module = module,
        platform = platform,
        isTest = false,
        userCacheRoot = userCacheRoot,
        incrementalCache = incrementalCache,
        quantifier = Quantifier.AnyOrNone,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        val linkedDir = dependenciesResult.requireSingleDependency<WebLinkTask.Result>().linkedBinary
            ?: userReadableError("Build an application without sources is not possible.")

        val fragments = module.fragments.filter {
            it.platforms.contains(platform) && it.isTest == isTest
        }

        val nodeModulesPath = dependenciesResult.requireSingleDependency<NpmInstallTask.Result>().nodeModulesPath
        val importMap = nodeModulesPath
            ?.let(::generateImportMap) ?: emptyMap()

        val resourcesPaths = fragments
            .map { it.resourcesPath }
            .filter { it.exists() }

        val composeResourcesPaths = composeResources.map { it.path }.filter { it.isDirectory() }

        val skikoWasmRuntime: Path? = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.runtimeClasspath }
            .distinct()
            .filter { it.extension == "jar" }
            .singleOrNull { it.nameWithoutExtension.startsWith(SKIKO_WASM_RUNTIME) }

        incrementalCache.executeForFiles(
            taskName.id.value,
            inputValues = importMap.mapValues { it.value.invariantSeparatorsPathString },
            inputFiles = listOfNotNull(linkedDir, skikoWasmRuntime) + resourcesPaths + composeResourcesPaths,
        ) {
            taskOutputPath.path.clean()

            BuildPrimitives.copy(
                from = linkedDir,
                to = taskOutputPath.path,
                overwrite = true,
            )

            composeResourcesPaths
                .forEach { composeResourcesDir ->
                    BuildPrimitives.copy(
                        from = composeResourcesDir,
                        to = taskOutputPath.path,
                        overwrite = true,
                    )
                }

            resourcesPaths
                .forEach { resource ->
                    BuildPrimitives.copy(
                        from = resource,
                        to = taskOutputPath.path,
                        overwrite = true,
                    )
                }

            if (taskOutputPath.path.listDirectoryEntries("index.html").isEmpty()) {
                createIndexHtml()
            } else {
                processIndexHtml()
            }

            processNodeModulesWithImportMap(importMap, nodeModulesPath)

            if (skikoWasmRuntime != null) {
                copySkikoWasmRuntime(skikoWasmRuntime)
            }

            listOf(taskOutputPath.path)
        }

        return Result(taskOutputPath.path)
    }

    private suspend fun processNodeModulesWithImportMap(
        importMap: Map<String, Path>,
        nodeModulesPath: Path?,
    ) {
        val relativeImportMap = importMap.mapValues { [_, path] ->
            // if importMap is not empty, nodeModulesPath is not null
            val nodeModulesDir = nodeModulesPath!!
            val relativeFile = path.relativeTo(nodeModulesDir)

            "./$VENDORS/${relativeFile.invariantSeparatorsPathString}"
        }

        val result = mapOf("imports" to relativeImportMap)

        val resultImportMapLoader = taskOutputPath.path.resolve("import-map-loader.js")

        val importMapString = json.encodeToString(result)
        resultImportMapLoader.writeText(
            """
                |const script = document.createElement('script');
                |script.type = 'importmap';
                |script.textContent = JSON.stringify($importMapString);
                |document.currentScript.after(script);
                """.trimMargin()
        )

        nodeModulesPath?.let {
            copyNodeModulesToVendors(importMap.keys, it)
        }
    }

    private suspend fun copyNodeModulesToVendors(
        importMap: Set<String>,
        nodeModulesPath: Path,
    ) {
        importMap
            .forEach { name ->
                BuildPrimitives.copy(
                    from = nodeModulesPath / name,
                    to = taskOutputPath.path
                        .resolve(VENDORS)
                        .resolve(name)
                        .also { it.createDirectories() },
                    overwrite = true,
                )
            }
    }

    private suspend fun copySkikoWasmRuntime(skikoWasmRuntime: Path) {
        val skikoWasmRuntimeExtracted = tempRoot.path.resolve(SKIKO_WASM_RUNTIME)

        extractZip(
            skikoWasmRuntime,
            skikoWasmRuntimeExtracted,
            stripRoot = false,
        )

        skikoWasmRuntimeExtracted
            .listDirectoryEntries()
            .filter { it.name in SKIKO_WASM_RUNTIME_FILES }
            .forEach {
                BuildPrimitives.copy(
                    from = it,
                    to = taskOutputPath.path.resolve(it.fileName),
                    overwrite = true,
                )
            }
    }

    private fun createIndexHtml() {
        val (moduleName, _ = moduleFile, scriptLines) = indexHtmlDefaultTemplateValues()

        taskOutputPath.path.resolve("index.html").writeText(
            """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |    <meta charset="UTF-8">
            |    <title>$moduleName</title>
            |${scriptLines.prependIndent("    ")}
            |</head>
            |<body>
            |
            |</body>
            |</html>
            """.trimMargin()
        )
    }

    private fun processIndexHtml() {
        val (moduleName, moduleFile, scriptLines) = indexHtmlDefaultTemplateValues()

        val indexHtml = taskOutputPath.path.resolve("index.html")
        val content = indexHtml.readText()
            .replace("{{kotlin.moduleName}}", moduleName)
            .replace("{{kotlin.moduleFile}}", moduleFile)
            .replace(
                Regex(
                    """([ \t]*)\{\{kotlin.scripts}}""",
                )
            ) { match ->
                // try to keep indent for pretty printing
                val indent = match.groupValues[1]

                scriptLines.prependIndent(indent)
            }
        indexHtml.writeText(content)
    }

    private fun indexHtmlDefaultTemplateValues(): IndexHtmlDefaults {
        val moduleName = module.kotlinModuleName(isTest)
        val moduleFile = "$moduleName.mjs"
        val scriptLines = """
                <script src="import-map-loader.js"></script>
                <script src="$moduleFile" type="module"></script>
            """.trimIndent()
        return IndexHtmlDefaults(
            moduleName,
            moduleFile,
            scriptLines
        )
    }

    private class IndexHtmlDefaults(
        val moduleName: String,
        val moduleFile: String,
        val scriptLines: String,
    )

    class Result(
        val appPath: Path,
    ) : TaskResult
}

private const val SKIKO_WASM_RUNTIME = "skiko-js-wasm-runtime"
private const val SKIKO_MJS = "skiko.mjs"
private const val SKIKO_WASM = "skiko.wasm"
private val SKIKO_WASM_RUNTIME_FILES = setOf(
    SKIKO_MJS,
    SKIKO_WASM,
)