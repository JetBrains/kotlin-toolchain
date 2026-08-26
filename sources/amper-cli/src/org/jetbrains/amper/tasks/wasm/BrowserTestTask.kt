/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.TestTask
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.nodejs.downloadNodeJs
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.web.NODE_JS_VERSION
import org.jetbrains.amper.tasks.web.NpmInstallTask
import org.jetbrains.amper.tasks.web.VENDORS
import org.jetbrains.amper.tasks.web.downloadPnpm
import org.jetbrains.amper.teamcity.events.TeamCityMessageProcessor
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val TEST_RUN_TIMEOUT: Duration = 30.seconds

private val FLOW_ID_REGEX = Regex(" flowId='[^']*'")

class BrowserTestTask(
    override val taskName: TaskName,
    override val platform: Platform,
    override val buildType: BuildType,
    override val module: AmperModule,
    private val processRunner: ProcessRunner,
    private val runSettings: AllRunSettings,
    private val userCacheRoot: AmperUserCacheRoot,
) : TestTask {

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        val builtApp = dependenciesResult.requireSingleDependency<WasmJsBuildTaskBase.Result>().appPath
            ?: return EmptyTaskResult
        val nodeModulesPath = dependenciesResult.requireSingleDependency<NpmInstallTask.Result>().nodeModulesPath

        return coroutineScope {
            val server = setupServer(
                port = 0,
                appDirectory = builtApp,
            ).also { server ->
                server.application.routing {
                    nodeModulesPath?.let {
                        staticFiles("/$VENDORS", it.toFile())
                    }
                }
            }.startSuspend(wait = false)

            try {
                val testFilterParameter = runSettings.testFilters.toTestFilterArg()?.let {
                    parametersOf(TEST_FILTER_QUERY_PARAM, it)
                }

                val url = server.engine.getUrl(
                    segment = TEST_PAGE_NAME,
                    parameters = testFilterParameter ?: Parameters.Empty,
                )

                logger.debug("Opening URL: $url")

                val nodeExecutable = downloadNodeJs(userCacheRoot, NODE_JS_VERSION)
                val pnpm = downloadPnpm(userCacheRoot)

                spanBuilder("wasm-js-browser-test-install-browsers")
                    .setAttribute("pnpm", pnpm.absolutePathString())
                    .use {
                        val result = processRunner.runProcess(
                            workingDir = builtApp,
                            command = [
                                nodeExecutable.absolutePathString(),
                                pnpm.absolutePathString(),
                                "dlx",
                                "--silent",
                                "playwright@1.61.0",
                                "install",
                                "chromium",
                                "--no-shell"
                            ],
                            span = it,
                            environment = emptyMap(),
                            outputMode = ProcessOutputMode.listenAndCaptureStderr(
                                listener = LoggingProcessOutputListener(logger),
                            ),
                        )

                        if (result.exitCode != 0) {
                            userReadableError(
                                "Failed to install Chromium browser for Kotlin/Wasm $platform tests for module '${module.userReadableName}'"
                            )
                        }
                    }

                val allTestsSucceeded = spanBuilder("wasm-js-browser-test")
                    .setAttribute("url", url)
                    .use { runTestsInBrowser(url = url, nodeExecutable = nodeExecutable) }

                if (!allTestsSucceeded) {
                    userReadableError("Kotlin/Wasm $platform tests failed for module '${module.userReadableName}' (see errors above)")
                }

                EmptyTaskResult
            } finally {
                server.stop()
            }
        }
    }

    context(executionContext: TaskGraphExecutionContext)
    private suspend fun runTestsInBrowser(url: String, nodeExecutable: Path): Boolean {
        val failedTests = mutableSetOf<TestId>()

        // The Playwright API is thread-affine: all calls have to happen on the thread that created the Playwright instance.
        @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
        newSingleThreadContext(taskName.id.value).use { context ->
            withContext(context) {
                var finished = false

                val createOptions = Playwright.CreateOptions().setEnv(
                    mapOf(
                        "PLAYWRIGHT_NODEJS_PATH" to nodeExecutable.pathString,
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1",
                    )
                )

                val teamCityMessageProcessor = TeamCityMessageProcessor(
                    executionContext.eventSink,
                    onTestFailed = { id, _ ->
                        failedTests.add(id)
                    }
                )

                Playwright.create(createOptions).use { playwright ->
                    val launchOptions = BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setChannel("chromium")
                    playwright.chromium().launch(launchOptions).use { browser ->
                        browser.newPage().use { page ->
                            page.setDefaultTimeout(TEST_RUN_TIMEOUT.inWholeMilliseconds.toDouble())

                            page.onConsoleMessage { message ->
                                val line = message.text()
                                if (line.startsWith(TESTS_FINISHED_MARKER)) {
                                    finished = true
                                } else {
                                    // kotlin-test for wasm generates random flow id, it breaks nesting
                                    val lineWithoutFlowId = line.replace(FLOW_ID_REGEX, "")
                                    teamCityMessageProcessor.parse(lineWithoutFlowId, stderr = false)
                                }
                            }

                            page.navigate(url)
                            page.waitForCondition { finished }
                        }
                    }
                }
            }
        }

        return failedTests.isEmpty()
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
