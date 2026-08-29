/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.wrapper.AmperWrapperData
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

@TaskAction
fun generateLocalDemoBrewFormula(
    @Input hostWrapperScript: Path,
    @Output formula: Path,
    @Output archive: Path,
) {
    require(archive.extension == "zip") { "archive '$archive' must be a zip file" }

    formula.createParentDirectories()
    archive.createParentDirectories()

    archive.deleteIfExists()

    val (version, sha256) = AmperWrapperData.parse(hostWrapperScript)
    println("Using host Kotlin CLI version $version ($sha256) for the global script")

    val options = mapOf(
        "create" to "true",
        "enablePosixFileAttributes" to "true",
    )
    FileSystems.newFileSystem(archive, options).use { zipFs ->
        AmperWrappers.generate(
            amperVersion = version,
            amperDistTgzSha256 = sha256,
            targetDir = zipFs.getPath("kotlin").createDirectory(),
            includeWindows = false,
        )
    }

    formula.writeText(
        """
        # Local formula for debug purposes. Can be installed without a tap.
        class Kotlin < Formula
          desc "Global Kotlin wrapper script"
          homepage "https://kotlin-toolchain.org"
          url "file:///${archive.absolutePathString()}"
          sha256 "${archive.sha256String()}"
          version "${version}+debug-${System.currentTimeMillis()}"
          license "Apache-2.0"

          def install
            bin.install "kotlin"
          end
        end
    """.trimIndent()
    )

    println("Formula is available in: $formula")
}

@TaskAction
fun installBrewFormula(
    @Input formula: Path,
) {
    val commandLine = listOf(
        "brew",
        "install",
        "--formula",
        formula.absolutePathString(),
    )
    val environment = mapOf(
        // To allow installing formulas outside a tap.
        "HOMEBREW_DEVELOPER" to "1",
    )

    val process = ProcessBuilder(commandLine).run {
        environment().putAll(environment)
        start()
    }

    val stdErrReader = thread { process.errorStream.copyTo(System.out) }
    val stdOutReader = thread { process.inputStream.copyTo(System.out) }

    val exitCode = process.waitFor()
    stdErrReader.join()
    stdOutReader.join()

    check(exitCode == 0) {
        "brew install failed with exit code $exitCode"
    }
}
