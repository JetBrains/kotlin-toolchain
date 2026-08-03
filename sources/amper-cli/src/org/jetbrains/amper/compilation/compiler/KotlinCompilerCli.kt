/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation.compiler

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

context(userCacheRoot: AmperUserCacheRoot, processRunner: ProcessRunner)
suspend fun provisionKotlinCompilerCli(version: String): KotlinCompilerCli = spanBuilder("Provision Kotlin compiler")
    .setAttribute("version", version)
    .use {
        val distZipUrl = "https://github.com/JetBrains/kotlin/releases/download/v$version/kotlin-compiler-$version.zip"
        val distZip = Downloader.downloadFileToCacheLocation(distZipUrl, userCacheRoot = userCacheRoot)
        val kotlincHome = extractFileToCacheLocation(distZip, userCacheRoot, ExtractOptions.STRIP_ROOT)
        KotlinCompilerCli(homeDir = kotlincHome, processRunner)
    }

class KotlinCompilerCli(
    private val homeDir: Path,
    private val processRunner: ProcessRunner,
) {
    // not lazy so we immediately validate that the kotlinc executable is present
    private val kotlinc: Path = run {
        val executableName = if (OsFamily.current.isWindows) "kotlinc.bat" else "kotlinc"
        val executablePath = homeDir / "bin" / executableName
        if (!executablePath.exists()) {
            error("kotlinc executable not found under Kotlin home dir: $executablePath")
        }
        // Patching here will not mess with the download cache because it only checks the number of top-level dirs, not
        // the actual contents of the extracted dir, for better or for worse.
        if (executableName == "kotlinc.bat") {
            executablePath.patchKT87493()
        }
        executablePath
    }

    private fun Path.patchKT87493() {
        writeText(readText().patchKT87493())
    }

    // See https://youtrack.jetbrains.com/issue/KT-87493
    private fun String.patchKT87493(): String = replace("""
        :set_home
          set _BIN_DIR=
          for %%i in (%~sf0) do set _BIN_DIR=%_BIN_DIR%%%~dpsi
          set _KOTLIN_HOME=%_BIN_DIR%..
        goto :eof
    """.trimIndent().replace("\n", "\r\n"), """
        :set_home
          set "_KOTLIN_HOME=%~dp0.."
        goto :eof
    """.trimIndent().replace("\n", "\r\n"))

    suspend fun <R : ProcessResult> runKotlinScript(
        scriptPath: Path,
        workingDir: Path,
        jdkHome: Path,
        args: List<String>,
        input: ProcessInput,
        outputMode: ProcessOutputMode<R>,
    ): R = spanBuilder("Run Kotlin script")
        .setAttribute("script-path", scriptPath.pathString)
        .use {
            processRunner.runProcess(
                workingDir = workingDir,
                command = [kotlinc.pathString, "-script", scriptPath.pathString] + args,
                environment = mapOf("JAVA_HOME" to jdkHome.pathString),
                input = input,
                outputMode = outputMode,
            )
        }
}
