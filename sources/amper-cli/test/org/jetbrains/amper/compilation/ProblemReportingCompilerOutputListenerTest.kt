/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemReportingCompilerOutputListenerTest {

    private val reporter = CollectingProblemReporter()
    private val listener = ProblemReportingCompilerOutputListener(
        reporter = reporter,
        moduleName = "my-module",
        workingDir = Path("/home/me/konan"),
        logger = LoggerFactory.getLogger(ProblemReportingCompilerOutputListenerTest::class.java),
    )

    @Test
    fun `reports warnings printed on stderr as warnings, not errors`() {
        listener.onStderrLine("warning: flag is not supported by this version of the compiler: -Xfoo-bar", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        @OptIn(NonIdealDiagnostic::class)
        assertEquals(
            [
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "flag is not supported by this version of the compiler: -Xfoo-bar",
                    level = Level.Warning,
                )
            ],
            reporter.problems,
        )
        assertEquals(0, listener.errorCount)
    }

    @Test
    fun `reports located messages with their source location`() {
        [
            "/home/me/project/src/Foo.kt:5:13: error: unresolved reference 'undefinedThing'.",
            "    println(undefinedThing)",
            "            ^^^^^^^^^^^^^^",
        ].forEach { listener.onStderrLine(it, pid = 1) }
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            [
                FileCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "unresolved reference 'undefinedThing'.",
                    level = Level.Error,
                    source = CompilerBuildProblemSource(
                        file = Path("/home/me/project/src/Foo.kt"),
                        line = 5,
                        column = 13,
                        lineEnd = 5,
                        columnEnd = 27,
                    ),
                )
            ],
            reporter.problems,
        )
        assertEquals(1, listener.errorCount)
    }

    @Test
    fun `resolves relative message paths against the working directory of the compiler`() {
        [
            "src/Foo.kt:5: error: some error",
            "src/Foo.kt:6: error: some other error",
        ].forEach { listener.onStderrLine(it, pid = 1) }
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            [Path("/home/me/konan/src/Foo.kt"), Path("/home/me/konan/src/Foo.kt")],
            reporter.problems.map { (it as FileCompilerBuildProblem).source.file },
        )
    }

    @Test
    fun `does not report non-diagnostic messages nor unrecognized lines as problems`() {
        [
            "logging: using Kotlin home directory dist/kotlinc",
            "info: some information about the compilation",
            "output: some output file",
            "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called",
        ].forEach { listener.onStderrLine(it, pid = 1) }
        listener.onStdoutLine("some plain output of the compiler", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals([], reporter.problems)
        assertEquals(0, listener.errorCount)
    }

    @Test
    fun `reports messages from both streams independently`() {
        listener.onStderrLine("error: some error on stderr", pid = 1)
        listener.onStdoutLine("warning: some warning on stdout", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            ["some error on stderr" to Level.Error, "some warning on stdout" to Level.Warning],
            reporter.problems.map { it.message to it.level },
        )
    }
}
