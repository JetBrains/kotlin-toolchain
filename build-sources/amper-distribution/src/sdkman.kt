/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

@TaskAction
fun buildSdkmanArchive(
    @Output archive: Path,
    @Input distribution: Distribution,
    @Input licenseFile: Path,
) {
    require(archive.extension == "zip") { "archive '$archive' must be a zip file" }

    archive.createParentDirectories()
    archive.deleteIfExists()

    val stagingDir = createTempDirectory()
    try {
        val rootDir = stagingDir / "$SdkmanCandidateName-${AmperBuild.mavenVersion}"
        val binDir = rootDir / "bin"
        binDir.createDirectories()

        AmperWrappers.generate(
            targetDir = binDir,
            amperVersion = AmperBuild.mavenVersion,
            amperDistTgzSha256 = distribution.cliTgz.sha256String(),
        )
        (rootDir / "license").writeBytes(licenseFile.readBytes())

        println("Writing SDKMAN archive to $archive")
        archive.writeSdkmanArchive(rootDir)
    } finally {
        stagingDir.deleteRecursively()
    }
}

private fun Path.writeSdkmanArchive(rootDir: Path) {
    ZipArchiveOutputStream(outputStream().buffered()).use { zipStream ->
        rootDir.walk(PathWalkOption.INCLUDE_DIRECTORIES)
            .sortedBy { it.relativeTo(rootDir.parent).invariantSeparatorsPathString }
            .forEach { path ->
                zipStream.writeSdkmanEntry(
                    path = path,
                    pathInZip = path.relativeTo(rootDir.parent).invariantSeparatorsPathString,
                )
            }
    }
}

private fun ZipArchiveOutputStream.writeSdkmanEntry(path: Path, pathInZip: String) {
    val isDirectory = path.isDirectory()
    val entryName = if (isDirectory) "$pathInZip/" else pathInZip
    val entry = ZipArchiveEntry(path, entryName).also {
        it.unixMode = when {
            isDirectory -> directoryMode
            path.name == "kotlin" -> executableFileMode
            else -> regularFileMode
        }
    }
    putArchiveEntry(entry)
    if (!isDirectory) {
        path.inputStream().use { it.copyTo(this) }
    }
    closeArchiveEntry()
}

private val directoryMode = "755".toInt(8)
private val executableFileMode = "755".toInt(8)
private val regularFileMode = "644".toInt(8)

private const val SdkmanCandidateName = "kotlintoolchain"
