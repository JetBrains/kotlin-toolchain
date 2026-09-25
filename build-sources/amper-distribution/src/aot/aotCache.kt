/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

package aot

import Repository
import maven.createSimplePom
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import maven.kotlinToolchainArtifact
import maven.publish
import org.eclipse.aether.artifact.Artifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.stdlib.io.path.withTempDir
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.wrapper.AmperWrapperData

/**
 * Trains a JVM AOT cache for the Kotlin CLI, by running a build of the project in [trainingProjectDir] with
 * `-XX:AOTCacheOutput`. The JVM records the classes that were loaded and the methods that were hot, and assembles the
 * cache into [cacheFile] when the CLI exits.
 *
 * The CLI that is trained is the one provisioned by the wrapper scripts of [trainingProjectDir] (they must exist).
 * The resulting cache is only valid for that exact distribution version, its JRE, and the current OS/architecture.
 *
 * The training run is an incremental build: everything is built first, then some file is modified so that the second
 * build actually compiles (the Kotlin compiler accounts for most of the cached classes) while staying close to a
 * developer's everyday build. The file is restored afterwards.
 */
@TaskAction
fun trainAotCache(
    @Input trainingProjectDir: Path,
    @Output cacheFile: Path,
) {
    val wrapper = trainingProjectDir.kotlinWrapperPath()
    cacheFile.createParentDirectories().deleteIfExists()

    // Training on a hot build reduces the AOT cache size compared to a full build
    println("Warming up the caches and build outputs of $trainingProjectDir")
    wrapper.runKotlinCli("build")

    withSmallSourceFileChange(trainingProjectDir) {
        println("Training the AOT cache into $cacheFile")
        // Using a /-separated path even on Windows, because KOTLIN_CLI_JAVA_OPTIONS is parsed like a command line,
        // so backslashes would escape the next character.
        wrapper.runKotlinCli("build", jvmOptions = "-XX:AOTCacheOutput=${cacheFile.invariantSeparatorsPathString}")
    }

    check(cacheFile.exists()) { "The training run did not produce an AOT cache at $cacheFile" }
    println("""
        AOT cache training complete:
        path: $cacheFile
        size: ${cacheFile.fileSize() / 1024 / 1024}MB
    """)
}

private fun Path.kotlinWrapperPath(): Path {
    val wrapper = this / if (SystemInfo.CurrentHost.family.isWindows) "kotlin.bat" else "kotlin"
    check(wrapper.exists()) {
        "No Kotlin wrapper script found at $wrapper. The training project doesn't have checked-in wrappers.\n" +
                "Generate them with 'kotlin update --create --target-dir=$this --target-version=<version>'."
    }
    return wrapper
}

private fun withSmallSourceFileChange(projectDir: Path, block: () -> Unit) {
    val sourceFile = projectDir / "core/src/org/jetbrains/aottraining/core/Catalog.kt"
    val originalContents = sourceFile.readText()
    try {
        sourceFile.writeText("$originalContents\n// Modified by the AOT cache training run\n")
        block()
    } finally {
        sourceFile.writeText(originalContents)
    }
}

/**
 * Compresses the given [aotCacheFile] and publishes it to the given [repository], under the version that
 * [trainingProjectDir] is pinned to. A pom.xml and checksums are also published together with the archive.
 *
 * Each platform has its own Maven module (see [artifactIdForCurrentPlatform]), so the machine that trained a
 * cache can publish it on its own: there is no shared POM or `maven-metadata.xml` that concurrent publications could
 * race on.
 */
@TaskAction
fun uploadAotCache(
    @Input aotCacheFile: Path,
    @Input trainingProjectDir: Path,
    repository: Repository,
) {
    val artifactId = artifactIdForCurrentPlatform()
    val version = AmperWrapperData.parseFromProjectRoot(trainingProjectDir)?.version
        ?: error("No Kotlin wrapper script found in $trainingProjectDir, cannot tell which Kotlin CLI version the cache is for")

    withTempDir { tempDir ->
        val artifacts = generateAotCacheArtifacts(tempDir, aotCacheFile, artifactId, version)
        println("Publishing $artifactId:$version to $repository")
        repository.publish(artifacts)
    }
}

/**
 * Builds the Maven artifacts for the AOT cache trained at [cacheFile]: the compressed cache itself, and the POM of
 * the platform-specific module it belongs to.
 */
private fun generateAotCacheArtifacts(tempDirectory: Path, cacheFile: Path, artifactId: String, version: String): List<Artifact> {
    val extension = "aot.gz"
    val compressedCachePath = tempDirectory / "$artifactId-$version.$extension"
    cacheFile.gzipTo(compressedCachePath)
    println("Compressed the AOT cache into $compressedCachePath (${compressedCachePath.fileSize() / 1024 / 1024}MB)")

    val cacheArtifact = kotlinToolchainArtifact(
        artifactId = artifactId,
        file = compressedCachePath,
        version = version,
        extension = extension,
    )
    // we also generate a POM file to please maven and ensure maven-metadata.xml is properly updated
    val pomFile = createSimplePom(tempDirectory, artifactId, version)

    val pomArtifact = kotlinToolchainArtifact(
        artifactId = artifactId,
        file = pomFile,
        version = version,
    )
    return [cacheArtifact, pomArtifact]
}

private fun Path.gzipTo(outputPath: Path) {
    outputPath.outputStream().buffered().use { fileOut ->
        GZIPOutputStream(fileOut).use { output ->
            this@gzipTo.inputStream().buffered().use { input -> input.copyTo(output) }
        }
    }
}

private fun Path.runKotlinCli(vararg args: String, jvmOptions: String? = null) {
    val command = listOf(pathString) + args
    println("Running: ${command.joinToString(" ")}")

    val result = runBlocking {
        runProcess(
            workingDir = parent,
            command = command,
            outputMode = ProcessOutputMode.listen(object : ProcessOutputListener {
                override fun onStdoutLine(line: String, pid: Long) {
                    println(line)
                }
                override fun onStderrLine(line: String, pid: Long) {
                    System.err.println(line)
                }
            }),
            configureEnvironment = {
                put("KOTLIN_CLI_NO_WELCOME_BANNER", "1")
                // We need to replace existing Java options from the outer CLI, as it could affect the trained cache
                remove("KOTLIN_CLI_JAVA_OPTIONS")
                if (jvmOptions != null) {
                    put("KOTLIN_CLI_JAVA_OPTIONS", jvmOptions)
                }
            }
        )
    }
    val exitCode = result.exitCode.value
    check(exitCode == 0) {
        "The command '${command.joinToString(" ")}' failed with exit code $exitCode"
    }
}

/**
 * The Maven artifactId of the AOT cache for the current host platform, for instance
 * `kotlin-cli-aot-cache-linux-x64`.
 *
 * Each platform has its own Maven module because a cache is only usable on the platform it was trained on, and
 * because the caches of the different platforms are trained and published independently, on different machines.
 *
 * The platform naming must stay in sync with `provision_aot_cache` in `launcher.template.sh`, which is what
 * downloads these artifacts.
 */
private fun artifactIdForCurrentPlatform(): String = "kotlin-cli-aot-cache-$osAndArch"

private val osAndArch: String
    get() {
        val os = when (val family = SystemInfo.CurrentHost.family) {
            OsFamily.Linux -> "linux"
            OsFamily.MacOs -> "macos"
            OsFamily.Windows -> "windows"
            else -> error("AOT caches are not supported on $family")
        }
        val arch = when (SystemInfo.CurrentHost.arch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "aarch64"
        }
        return "$os-$arch"
    }
