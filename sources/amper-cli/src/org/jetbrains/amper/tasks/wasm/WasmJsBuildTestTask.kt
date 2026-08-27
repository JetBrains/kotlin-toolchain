/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

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
import kotlin.io.path.name
import kotlin.io.path.writeText

class WasmJsBuildTestTask(
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
        get() = true

    override val nodeModulesPrefix: String
        get() = "/$VENDORS"

    override fun processHtmlFile() {
        createTestHtml()
    }

    private fun createTestHtml() {
        val loaderMjs = createLoader()
        val scriptLines = scriptLines(loaderMjs.name)

        //language=html
        taskOutputPath.path.resolve(TEST_PAGE_NAME).also { indexHtml ->
            indexHtml.writeText(
                """
            |<!DOCTYPE html>
            |<html>
            |<head>
            |    <meta charset="utf-8">
            |    <link rel="icon" href="#" />
            |    <title>Kotlin Wasm Tests</title>
            |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            |</head>
            |<body>
            |${scriptLines.prependIndent("    ")}
            |</body>
            |</html>
            """.trimMargin()
            )
        }
    }

    private fun createLoader(): Path {
        val (_ = moduleName, moduleFile, _ = scriptLines) = indexHtmlDefaultTemplateValues()

        return taskOutputPath.path.resolve("loader.mjs").also {
            it.writeText(browserTestLoaderScript(testModuleFile = moduleFile))
        }
    }
}