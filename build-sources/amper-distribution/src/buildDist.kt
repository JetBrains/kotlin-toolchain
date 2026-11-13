/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

@TaskAction
fun buildDist(
    @Output distribution: Distribution,
    @Input unpackedDistDir: Path,
) {
    val cliTgzPath = distribution.cliTgz.createParentDirectories()

    println("Writing CLI distribution to $cliTgzPath")
    unpackedDistDir.tarGzTo(cliTgzPath)

    val wrappers = AmperWrappers.generate(
        targetDir = distribution.wrappersDir.createDirectories(),
        amperVersion = AmperBuild.mavenVersion,
        amperDistTgzSha256 = cliTgzPath.sha256String(),
    )

    AmperWrappers.generateInstallers(
        targetDir = distribution.installersDir.createDirectories(),
        amperVersion = AmperBuild.mavenVersion,
        wrappers = wrappers,
    )
}

private fun Path.tarGzTo(archivePath: Path) {
    TarArchiveOutputStream(GZIPOutputStream(archivePath.outputStream().buffered())).use { tarStream ->
        walk().filter { it.isRegularFile() }.forEach { file ->
            tarStream.writeFile(file, file.relativeTo(this).pathString)
        }
    } 
}

private fun TarArchiveOutputStream.writeFile(file: Path, pathInTar: String) {
    val entry = TarArchiveEntry(file, pathInTar)
    putArchiveEntry(entry)
    file.inputStream().use { input -> input.copyTo(this) }
    closeArchiveEntry()
}
