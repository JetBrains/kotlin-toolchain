/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(NonIdealDiagnostic::class)
class CInteropOutputListenerTest {

    private val reporter = CollectingProblemReporter()
    private val listener = CInteropOutputListener(
        reporter = reporter,
        moduleName = "my-module",
        logger = LoggerFactory.getLogger(CInteropOutputListenerTest::class.java),
    )

    @Test
    fun `reports a single warning listing the skipped non-importable modules`() {
        // real output of cinterop 2.4.0 with skipNonImportableModules = true (KTC-5820)
        [
            "java.lang.Error: grpcpp: /p/PackageFrameworks/grpc.framework/Headers/event_engine/endpoint_config.h:19:10: fatal error: 'string' file not found",
            "grpcpp: /p/PackageFrameworks/absl.framework/Headers/algorithm/algorithm.h:25:10: fatal error: 'algorithm' file not found",
            "grpc: /p/PackageFrameworks/grpc.framework/Modules/module.modulemap:2:17: error: umbrella header 'gRPC-Core-umbrella.h' not found",
            "openssl_grpc: /p/PackageFrameworks/openssl_grpc.framework/Headers/internal.h:1:10: fatal error: 'internal.h' file not found",
            "absl: /var/folders/T/4942355504760821608.m:1:9: fatal error: module 'absl' not found",
            "leveldb: /p/checkouts/leveldb/include/leveldb/cache.h:21:10: fatal error: 'cstdint' file not found",
        ].forEach { listener.onStdoutLine(it, pid = 1) }
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            [
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "cinterop skipped the following modules because they cannot be imported to Kotlin " +
                            "(for instance, C++ modules): grpcpp, grpc, openssl_grpc, absl, leveldb. " +
                            "Run with --log-level=debug to see the underlying Clang diagnostics.",
                    level = Level.Warning,
                )
            ],
            reporter.problems,
        )
        assertEquals(0, listener.errorCount)
    }

    @Test
    fun `does not report anything for a successful run without skipped modules`() {
        listener.onStdoutLine("some plain output of cinterop", pid = 1)
        listener.onStderrLine("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals([], reporter.problems)
        assertEquals(0, listener.errorCount)
    }

    @Test
    fun `reports cinterop's own warnings as warning problems`() {
        // real format of the warnings printed by cinterop itself (see CommandLine.kt in the Kotlin repo)
        listener.onStdoutLine("warning: The package value 'foo' specified in .def file is overridden with explicit -pkg command line argument.", pid = 1)
        listener.onStdoutLine("warning: -linker-option(s)/-linkerOpts option is not supported by cinterop. Please add linker options to .def file or binary compilation instead.", pid = 1)
        listener.onStdoutLine("some plain output of cinterop", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            [
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "The package value 'foo' specified in .def file is overridden with explicit -pkg command line argument.",
                    level = Level.Warning,
                ),
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "-linker-option(s)/-linkerOpts option is not supported by cinterop. Please add linker options to .def file or binary compilation instead.",
                    level = Level.Warning,
                ),
            ],
            reporter.problems,
        )
        assertEquals(0, listener.errorCount)
    }

    @Test
    fun `does not confuse cinterop's own warnings with skipped modules`() {
        listener.onStdoutLine("java.lang.Error: cppTarget: /p/cppTarget/include/foo.h:1:10: fatal error: 'string' file not found", pid = 1)
        listener.onStdoutLine("warning: -Xklib-abi-compatibility-level is experimental", pid = 1)
        listener.onStreamsFlushed(exitCode = 0, pid = 1)

        assertEquals(
            [
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "-Xklib-abi-compatibility-level is experimental",
                    level = Level.Warning,
                ),
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = "cinterop skipped the following modules because they cannot be imported to Kotlin " +
                            "(for instance, C++ modules): cppTarget. " +
                            "Run with --log-level=debug to see the underlying Clang diagnostics.",
                    level = Level.Warning,
                ),
            ],
            reporter.problems,
        )
    }

    @Test
    fun `reports the whole output verbatim as a single error when cinterop fails`() {
        listener.onStdoutLine("some stdout line", pid = 1)
        [
            "",
            "Exception in thread \"main\" java.lang.Error: /p/cppTarget/include/foo.h:1:10: fatal error: 'string' file not found",
            "\tat org.jetbrains.kotlin.native.interop.indexer.ModuleSupportKt.getModulesASTFiles(ModuleSupport.kt:1)",
            "",
        ].forEach { listener.onStderrLine(it, pid = 1) }
        listener.onStreamsFlushed(exitCode = 1, pid = 1)

        assertEquals(
            [
                GlobalCompilerBuildProblem(
                    moduleName = "my-module",
                    message = """
                        cinterop failed with exit code 1:
                        Exception in thread "main" java.lang.Error: /p/cppTarget/include/foo.h:1:10: fatal error: 'string' file not found
                        ${"\t"}at org.jetbrains.kotlin.native.interop.indexer.ModuleSupportKt.getModulesASTFiles(ModuleSupport.kt:1)
                        
                        some stdout line
                    """.trimIndent(),
                    level = Level.Error,
                )
            ],
            reporter.problems,
        )
        assertEquals(1, listener.errorCount)
    }
}
