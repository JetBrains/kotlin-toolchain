/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import io.ktor.server.application.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.WebRunSettings
import org.jetbrains.amper.util.BuildType
import org.jetbrains.amper.util.openBrowser
import org.slf4j.LoggerFactory

private const val defaultWebBrowserRunPort = 8080

class BrowserRunTask(
    override val taskName: TaskName,
    override val platform: Platform,
    override val buildType: BuildType,
    override val module: AmperModule,
    private val runSettings: WebRunSettings,
) : RunTask {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        val port = runSettings.port ?: defaultWebBrowserRunPort
        val openBrowser = runSettings.openBrowser
        val builtApp = dependenciesResult.requireSingleDependency<WasmJsBuildTaskBase.Result>().appPath
            ?: return EmptyTaskResult

        return coroutineScope {
            val server = setupServer(
                port = port,
                appDirectory = builtApp,
            ).also { server ->
                if (openBrowser) {
                    server.monitor.subscribe(ApplicationStarted) {
                        launch {
                            openBrowser(it.engine.getUrl(), logger::debug)
                        }
                    }
                }
            }

            try {
                server.startSuspend(wait = true)
                EmptyTaskResult
            } finally {
                server.stop()
            }
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
