/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

@TaskAction
fun buildDist(
    @Output distribution: Distribution,
    @Input cliRuntimeClasspath: Classpath,
    @Input extraClasspaths: Map<String, Classpath>,
    @Input extraFilteredClasspaths: Map<String, FilteredClasspath>,
    @Input thirdPartyStagingDir: Path,
) {
    val cliTgz = distribution.cliTgz.createParentDirectories()

    val stagingDir = createTempDirectory()
    try {
        AmperWrappers.generateLaunchers(stagingDir / "bin")

        println("Writing CLI distribution to $cliTgz")
        cliTgz.writeDistTarGz(
            cliRuntimeClasspath = cliRuntimeClasspath.resolvedFiles,
            lazyClasspaths = lazyClasspathsLayout(extraClasspaths, extraFilteredClasspaths),
            mergeFrom = listOf(
                stagingDir,
                thirdPartyStagingDir,
            ),
        )
    } finally {
        stagingDir.deleteRecursively()
    }

    val wrappers = AmperWrappers.generate(
        targetDir = distribution.wrappersDir.createDirectories(),
        amperVersion = AmperBuild.mavenVersion,
        amperDistTgzSha256 = cliTgz.sha256String(),
    )

    AmperWrappers.generateInstallers(
        targetDir = distribution.installersDir.createDirectories(),
        amperVersion = AmperBuild.mavenVersion,
        wrappers = wrappers,
    )
}

private fun Path.writeDistTarGz(
    cliRuntimeClasspath: List<Path>,
    lazyClasspaths: LazyClasspathsLayout,
    mergeFrom: List<Path>,
) {
    TarArchiveOutputStream(GZIPOutputStream(outputStream().buffered())).use { tarStream ->
        tarStream.writeFile(contents = argFileContents(), pathInTar = "kotlin-cli.args")
        tarStream.writeDir(cliRuntimeClasspath, targetDirName = "lib")
        tarStream.write(lazyClasspaths)
        mergeFrom.forEach { stagingDir ->
            stagingDir.walk().forEach { file ->
                val pathInTar = file.relativeTo(stagingDir).pathString
                tarStream.writeFile(file, pathInTar)
            }
        }
    }
}

private fun TarArchiveOutputStream.write(extraClasspaths: LazyClasspathsLayout) {
    extraClasspaths.jars.forEach { (sourcePath, destPathInDist) ->
        writeFile(sourcePath, destPathInDist)
    }
    extraClasspaths.indexFiles.forEach { (contents, destPathInDist) ->
        writeFile(contents, destPathInDist)
    }
}

private fun TarArchiveOutputStream.writeDir(files: List<Path>, targetDirName: String) {
    val usedFileNames = DistinctFilenamePool()
    files.distinct().sortedBy { it.name }.forEach { path ->
        writeFile(path, "$targetDirName/${usedFileNames.registerAndGetName(path)}")
    }
}

private fun TarArchiveOutputStream.writeFile(file: Path, pathInTar: String) {
    val entry = TarArchiveEntry(file, pathInTar)
    putArchiveEntry(entry)
    file.inputStream().use { input -> input.copyTo(this) }
    closeArchiveEntry()
}

private fun TarArchiveOutputStream.writeFile(contents: String, pathInTar: String) {
    val bytes = contents.encodeToByteArray()
    val entry = TarArchiveEntry(pathInTar).also { it.size = bytes.size.toLong() }
    putArchiveEntry(entry)
    write(bytes)
    closeArchiveEntry()
}
