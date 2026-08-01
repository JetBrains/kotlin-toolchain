/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import io.opentelemetry.api.trace.Span
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.telemetry.setProcessResultAttributes
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getDefaultJdk
import org.jetbrains.amper.kotlin.native.KonanDistribution
import org.jetbrains.amper.kotlin.native.downloadAndExtractKotlinNative
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.runJava
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.ShellQuoting
import org.slf4j.LoggerFactory
import kotlin.io.path.div
import kotlin.io.path.pathString

context(_: ProblemReporter)
suspend fun downloadNativeCompiler(
    kotlinVersion: String,
    userCacheRoot: AmperUserCacheRoot,
    jdkProvider: JdkProvider,
): KotlinNativeCompiler {
    // TODO: Should we use KONAN_DATA_DIR here and elsewhere instead of Amper user cache folder?
    //  (as well as for the location of downloading compiler distribution itself.
    //  See AMPER-5319.
    val konanDistribution = Downloader.downloadAndExtractKotlinNative(kotlinVersion, userCacheRoot)
        ?: error("kotlin native compiler is not available for the current platform")

    // According to the Kotlin/Native team, no special requirements for this JDK, but they mostly test with 11.
    val jdk = jdkProvider.getDefaultJdk()
    return KotlinNativeCompiler(konanDistribution, jdk)
}

class KotlinNativeCompiler(
    val konanDistribution: KonanDistribution,
    val jdk: Jdk,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(KotlinNativeCompiler::class.java)
    }

    suspend fun compile(
        processRunner: ProcessRunner,
        args: List<String>,
        tempRoot: AmperProjectTempRoot,
        module: AmperModule,
    ) {
        spanBuilder("konanc")
            .setAmperModule(module)
            .setListAttribute("args", args)
            .setAttribute("version", konanDistribution.kotlinVersion)
            .use { span ->
                logger.debug("konanc ${ShellQuoting.quoteArgumentsPosixShellWay(args)}")

                withKotlinCompilerArgFile(args, tempRoot) { argFile ->
                    val result = runNativeCompilerCommandImpl(
                        processRunner = processRunner,
                        programArgs = listOf("konanc", "@${argFile}"),
                        argsMode = ArgsMode.ArgFile(tempRoot = tempRoot)
                    )
                    processNativeCompilerCommandResult(span, result, "native compilation")
                }
            }
    }

    suspend fun cinterop(
        processRunner: ProcessRunner,
        args: List<String>,
    ) {
        spanBuilder("cinterop")
            .setListAttribute("args", args)
            .setAttribute("version", konanDistribution.kotlinVersion)
            .use { span ->
                logger.debug("cinterop ${ShellQuoting.quoteArgumentsPosixShellWay(args)}")
                val result = runNativeCompilerCommandImpl(
                    processRunner = processRunner,
                    programArgs = listOf("cinterop") + args,
                    argsMode = ArgsMode.CommandLine,
                )
                processNativeCompilerCommandResult(span, result, "cinterop")
            }
    }

    private fun processNativeCompilerCommandResult(
        span: Span,
        result: ProcessResult.WithOutputs,
        moniker: String
    ) {
        // TODO this is redundant with the java span of the external process run. Ideally, we
        //  should extract higher-level information from the raw output and use that in this span.
        span.setProcessResultAttributes(result)

        if (result.exitCode != 0) {
            val errors = result.stderr
                .lines()
                .filter { it.startsWith("error: ") || it.startsWith("exception: ") }
                .joinToString("\n")
            val errorsPart = if (errors.isNotEmpty()) ":\n\n$errors" else ""
            userReadableError("$moniker failed$errorsPart")
        }
    }

    private suspend fun runNativeCompilerCommandImpl(
        processRunner: ProcessRunner,
        programArgs: List<String>,
        argsMode: ArgsMode,
    ): ProcessResult.WithOutputs {
        // We call konanc via java because the konanc command line doesn't support spaces in paths:
        // https://youtrack.jetbrains.com/issue/KT-66952

        // TODO in the future we'll switch to kotlin tooling api and remove this raw java exec anyway
        return processRunner.runJava(
            jdk = jdk,
            workingDir = konanDistribution.homeDir,
            mainClass = "org.jetbrains.kotlin.cli.utilities.MainKt",
            classpath = listOf(
                konanDistribution.konanLibDir / "kotlin-native-compiler-embeddable.jar",
                konanDistribution.konanLibDir / "trove4j.jar",
            ),
            programArgs = programArgs,
            argsMode = argsMode,
            // JVM args partially copied from <kotlinNativeHome>/bin/run_konan
            jvmArgs = listOf(
                "-ea",
                "-Xmx3G",
                "-XX:TieredStopAtLevel=1",
                "-Dfile.encoding=UTF-8",
                "-Dkonan.home=${konanDistribution.homeDir.pathString}",
            ),
            outputListener = LoggingProcessOutputListener(logger),
        )
    }
}
