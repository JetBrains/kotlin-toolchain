/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import java.net.InetAddress
import java.nio.file.Path

/**
 * The name of the generated HTML page that runs the tests of a module in a browser.
 */
internal const val TEST_PAGE_NAME = "test.html"

/**
 * The name of the repeatable URL query parameter that carries each kotlin-test filter argument
 * (e.g. `--include`/`--exclude`) to the test page running in a browser.
 */
internal const val KOTLIN_TEST_ARG_QUERY_PARAM = "kotlin-test-arg"

internal fun CoroutineScope.setupServer(
    port: Int,
    appDirectory: Path,
): EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration> {
    return embeddedServer(
        Netty,
        host = InetAddress.getLoopbackAddress().hostAddress,
        port = port
    ) {
        routing {
            staticFiles("/", appDirectory.toFile())
        }
    }
}

internal suspend fun ApplicationEngine.getUrl(
    segment: String? = null,
    parameters: Parameters = Parameters.Empty
): String {
    val type = resolvedConnectors().single().type
    val host = resolvedConnectors().single().host
    val port = resolvedConnectors().single().port
    return URLBuilder(
        protocol = type.toUrlProtocol(),
        host = host,
        port = port,
        pathSegments = listOfNotNull(segment),
        parameters = parameters,
    ).buildString()
}

private fun ConnectorType.toUrlProtocol() = when {
    this == ConnectorType.HTTP -> URLProtocol.HTTP
    this == ConnectorType.HTTPS -> URLProtocol.HTTPS
    else -> error("Unsupported connector type: $this")
}

/**
 * Reuse kotlin-web-helpers with mocha after [this PR](https://github.com/Kotlin/kotlin-web-helpers/pull/5) is merged
 */
internal const val TESTS_FINISHED_MARKER = "__KOTLIN_TOOLCHAIN_BROWSER_TESTS_FINISHED__"

private const val FIRST_SUITE_TIMEOUT_MS = 5_000

private const val QUIET_PERIOD_MS = 250

private const val POLL_INTERVAL_MS = 50

//language=js
internal fun browserTestLoaderScript(testModuleFile: String): String = """
    |import * as testModule from './$testModuleFile';
    |
    |// Read the '$KOTLIN_TEST_ARG_QUERY_PARAM' query parameters and expose them to the Kotlin test framework via
    |// globalThis.arguments, so that the test runner picks up the --include/--exclude filters.
    |const kotlinTestArgs = new URLSearchParams(globalThis.location.search).getAll('$KOTLIN_TEST_ARG_QUERY_PARAM');
    |if (kotlinTestArgs) {
    |    globalThis.arguments = kotlinTestArgs;
    |}
    |
    |const suiteStartedPrefix = '##teamcity[testSuiteStarted';
    |const suiteFinishedPrefix = '##teamcity[testSuiteFinished';
    |
    |let openSuites = 0;
    |let anySuiteSeen = false;
    |let lastMessageAt = performance.now();
    |
    |// We hook into console.log() to track the progress of the test run.
    |const consoleLog = console.log.bind(console);
    |console.log = (...args) => {
    |    const line = args.length === 1 ? String(args[0]) : args.join(' ');
    |    if (line.startsWith(suiteStartedPrefix)) {
    |        openSuites++;
    |        anySuiteSeen = true;
    |    } else if (line.startsWith(suiteFinishedPrefix)) {
    |        openSuites--;
    |    }
    |    lastMessageAt = performance.now();
    |    consoleLog(line);
    |};
    |
    |testModule['startUnitTests']?.();
    |
    |// startUnitTests() only kicks off the run: asynchronous tests keep going in the promise chain of
    |// TeamcityAdapterWithPromiseSupport. A test that is still pending keeps its enclosing suite unfinished,
    |// so 'no suite in progress' plus a short quiet period means the whole run is over, however slow the tests are.
    |const firstSuiteDeadline = performance.now() + $FIRST_SUITE_TIMEOUT_MS;
    |while (true) {
    |    if (anySuiteSeen && openSuites === 0 && performance.now() - lastMessageAt > $QUIET_PERIOD_MS) break;
    |    if (!anySuiteSeen && performance.now() > firstSuiteDeadline) break; // no tests in this module
    |    await new Promise(resolve => setTimeout(resolve, $POLL_INTERVAL_MS));
    |}
    |
    |consoleLog('$TESTS_FINISHED_MARKER');
    """.trimMargin()
