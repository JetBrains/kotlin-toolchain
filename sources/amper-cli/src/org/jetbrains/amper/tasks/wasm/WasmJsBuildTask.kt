/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.web.VENDORS
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WasmJsBuildTask(
    platform: Platform,
    module: AmperModule,
    buildType: BuildType,
    taskOutputPath: TaskOutputRoot,
    taskName: TaskName,
    tempRoot: AmperProjectTempRoot,
    incrementalCache: IncrementalCache,
    userCacheRoot: AmperUserCacheRoot,
) : WasmJsBuildTaskBase(
    platform,
    module,
    buildType,
    taskOutputPath,
    taskName,
    tempRoot,
    incrementalCache,
    userCacheRoot,
) {
    override val isTest: Boolean
        get() = false

    override val nodeModulesPrefix: String
        get() = "./$VENDORS"

    override fun processHtmlFile() {
        if (taskOutputPath.path.listDirectoryEntries("index.html").isEmpty()) {
            createIndexHtml()
        } else {
            processIndexHtml()
        }
    }

    override suspend fun processNodeModulesWithImportMap(
        importMap: Map<String, Path>,
        nodeModulesPath: Path?,
    ) {
        super.processNodeModulesWithImportMap(importMap, nodeModulesPath)

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

    private fun createIndexHtml() {
        val (moduleName, _ = moduleFile, scriptLines) = indexHtmlDefaultTemplateValues()

        // We add default CSS for the viewport to take the whole browser window
        // See https://kotlinlang.org/docs/multiplatform/compose-css-styles.html
        taskOutputPath.path.resolve("index.html").writeText(
            """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |    <meta charset="UTF-8">
            |    <style>
            |       html, body {
            |           width: 100%;
            |           height: 100%;
            |           margin: 0;
            |           padding: 0;
            |           overflow: hidden;
            |       }
            |    </style>
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
}