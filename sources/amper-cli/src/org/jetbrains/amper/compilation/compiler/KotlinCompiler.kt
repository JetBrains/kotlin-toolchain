/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation.compiler

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.majorVersion
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Downloads the implementation of the embeddable Kotlin compiler in the given [version].
 *
 * The [version] should match the Kotlin version requested by the user, it is the version of the Kotlin compiler
 * that will be used behind the scenes.
 */
context(_: ProblemReporter)
internal suspend fun KotlinArtifactsDownloader.downloadKotlinCompiler(version: String, jdk: Jdk): KotlinCompiler =
    KotlinCompiler(
        compilerJars = downloadKotlinCompilerEmbeddable(version),
        kotlinVersion = ComparableVersion(version),
        jdk = jdk,
    )

/**
 * A type-safe wrapper around the Kotlin compiler CLI.
 */
internal class KotlinCompiler(
    private val compilerJars: List<Path>,
    private val kotlinVersion: ComparableVersion,
    private val jdk: Jdk,
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(KotlinCompiler::class.java)

        private val KotlinVersionWithSeparateWasmCompiler = ComparableVersion("2.4.0")
    }

    context(processRunner: ProcessRunner)
    suspend fun compileMetadata(
        compilerArgs: List<String>,
        argsMode: ArgsMode.ArgFile,
    ): ProcessResult = compile(
        compilerArgs = compilerArgs,
        argsMode = argsMode,
        entryPoint = CompilerEntryPoint.Metadata,
    )

    context(processRunner: ProcessRunner)
    suspend fun compileWeb(
        compilerArgs: List<String>,
        argsMode: ArgsMode.ArgFile,
        webPlatform: Platform,
    ): ProcessResult = compile(
        compilerArgs = compilerArgs,
        argsMode = argsMode,
        entryPoint = when (webPlatform) {
            Platform.JS -> CompilerEntryPoint.JavaScript
            Platform.WASM_JS,
            Platform.WASM_WASI -> if (kotlinVersion >= KotlinVersionWithSeparateWasmCompiler) {
                // The separate KotlinWasmCompiler main class was only introduced in 2.4.0 (see KT-56850)
                CompilerEntryPoint.WebAssembly
            } else {
                CompilerEntryPoint.JavaScript
            }
            else -> error("Unsupported platform for web compilation: ${webPlatform.name}")
        },
    )

    context(processRunner: ProcessRunner)
    private suspend fun compile(
        compilerArgs: List<String>,
        argsMode: ArgsMode.ArgFile,
        entryPoint: CompilerEntryPoint,
        extraJvmArgs: List<String> = [],
    ): ProcessResult = processRunner.runJava(
        jdk = jdk,
        workingDir = Path("."),
        mainClass = entryPoint.mainClass,
        classpath = compilerJars,
        programArgs = compilerArgs,
        argsMode = argsMode,
        outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)),
        jvmArgs = buildList {
            // The Kotlin compiler relies on Jansi (now Jline), which uses native calls:
            // "java.lang.System::load has been called by org.jetbrains.kotlin.org.fusesource.jansi.internal.JansiLoader"
            // We need to enable native access on JDK 24+ to avoid a warning (and in the future an error).
            // See KT-76111, which was fixed by adding the JVM flag to the compiler start script.
            // Since we're launching Java by hand (not via the start script), we need to add it too.
            // See also https://jline.org/docs/troubleshooting/#jdk-24-restricted-method-warning, which is the
            // recommendation from Jline themselves.
            if (jdk.majorVersion >= 24) {
                // We can't specify the Jansi module specifically when using a classpath (we would need module path),
                // so we go for `ALL-UNNAMED`.
                add("--enable-native-access=ALL-UNNAMED")
            }
            addAll(extraJvmArgs)
        },
    )
}

private enum class CompilerEntryPoint(val mainClass: String) {
    Metadata(mainClass = "org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler"),
    JavaScript(mainClass = "org.jetbrains.kotlin.cli.js.K2JSCompiler"),
    WebAssembly(mainClass = "org.jetbrains.kotlin.cli.js.KotlinWasmCompiler"),
}
