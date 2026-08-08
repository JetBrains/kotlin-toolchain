/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.success
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.processes.startLongLivedProcess
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.ShellQuoting
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileAttributesViewOrNull
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isSameFileAs
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.system.exitProcess

private sealed class DesiredVersion {
    data class Latest(val includeDevVersions: Boolean) : DesiredVersion()
    data class SpecificVersion(val version: String) : DesiredVersion()
}

internal class UpdateCommand : AmperSubcommand(name = "update") {

    val targetDir by option(
        "--target-dir",
        help = "The directory in which to update (or create) the wrappers — usually a project's root directory. " +
                "By default, wrappers are updated (or created) in the current directory.",
    ).path(mustExist = true, canBeFile = false, canBeDir = true)
        /*
        We could also decide that the default is to update the currently running wrapper.
        (it can be implemented using the `KOTLIN_CLI_WRAPPER_PATH` env variable),
        but the benefit would be marginal, and it would break amper-from-sources.
        Let's not do anything until we handle the global Kotlin Toolchain installation / version in
        project.yaml story. See AMPER-5156 and AMPER-4104.
        */
        .default(Path("."))

    private val repository by option(
        "-r", "--repository",
        help = "The URL of the maven repository to download the Kotlin wrapper scripts from",
    ).default("https://packages.jetbrains.team/maven/p/amper/amper")

    private val desiredVersion by mutuallyExclusiveOptions(
        option("--dev", help = "Use the latest development version instead of the official release")
            .flag()
            .convert { DesiredVersion.Latest(includeDevVersions = it) },
        // avoid --version to avoid confusion with the "./kotlin --version" command
        option("--target-version", help = "The specific version to update to. By default, the latest version is used.")
            .convert { DesiredVersion.SpecificVersion(it) },
    )
        .single() // fail if both --dev and --target-version are used at the same time
        .default(DesiredVersion.Latest(includeDevVersions = false))

    private val create by option("-c", "--create", help = "Create the Kotlin wrappers if they don't exist yet")
        .flag()

    override fun help(context: Context): String = "Update the Kotlin Toolchain to the latest version or a specific version."

    override fun helpEpilog(context: Context): String =
        "This command can also be used to create Kotlin wrapper scripts in a directory if they don't exist yet."

    private val runningWrapper by lazy { Path(System.getenv("KOTLIN_CLI_WRAPPER_PATH")).absolute() }

    override suspend fun run() {
        val bashWrapperPath = targetDir.resolve("kotlin")
        val batWrapperPath = targetDir.resolve("kotlin.bat")
        checkNotDirectories(bashWrapperPath, batWrapperPath)
        if (!create) {
            confirmCreateIfMissingWrappers(bashWrapperPath, batWrapperPath)
        }

        val version = desiredVersion.resolve()

        terminal.println("Downloading Kotlin wrapper scripts...")
        val newBashWrapperPath = downloadWrapper(version = version, extension = "").apply { setReadExecPermissions() }
        val newBatWrapperPath = downloadWrapper(version = version, extension = ".bat").apply { setReadExecPermissions() }
        terminal.println("Download complete.")

        if (bashWrapperPath.exists() && newBashWrapperPath.readText() == bashWrapperPath.readText() &&
            batWrapperPath.exists() && newBatWrapperPath.readText() == batWrapperPath.readText()) {
            terminal.println("The Kotlin Toolchain is already in version $version, nothing to update")
            return
        }

        // Test the new script and download the Kotlin Toolchain distribution and JRE
        val result = spanBuilder("New version first run").use {
            runKotlinToolchainVersionFirstRun(newBatWrapperPath, newBashWrapperPath)
        }
        if (result.exitCode != 0) {
            userReadableError("Couldn't run the new Kotlin Toolchain version. Please check the errors above.")
        }

        // Replacing a bash script while it's running is possible. We use move commands to ensure the physical file on
        // disk is not modified, thus we can write a new physical file to the old location. Bash will keep loading the
        // old file incrementally from the old physical file using its old file descriptor, which is good.
        spanBuilder("Replace 'kotlin' script (bash)").use {
            copyAndReplaceSafely(source = newBashWrapperPath, target = bashWrapperPath)
        }

        // Batch files are different. When running, cmd.exe reloads the file after each command and tries to resume at
        // whatever byte offset it was. We can modify the file while it's running, but when the java command running
        // this code completes, it will resume in the new wrapper code. If the new script is shorter, cmd.exe will just
        // stop and the command completes normally. If the new script is longer, then cmd.exe will likely resume in the
        // middle of a command in the middle of the script, which will fail miserably.
        // Worse now: since the addition of 'call :exitWithErrorLevel' after the launch command, the running script will
        // always try to go to this label in the replaced file, so no matter the length, it will scan for this label. If
        // it doesn't find it, it will crash. If it does find it, it will run some new code that we don't want.
        // Because of this, we can never replace the running .bat script in place anymore. Even with atomic moves, the
        // new file is reloaded, so we have to spawn a process that will replace the old wrapper after the update
        // command (and the current wrapper) finished executing.
        val batUpdateInPlaceWouldBreak = batWrapperPath.exists() && batWrapperPath.isSameFileAs(runningWrapper)
        spanBuilder("Replace 'kotlin.bat' script").use { span ->
            if (batUpdateInPlaceWouldBreak) {
                copyAndReplaceLaterWindows(source = newBatWrapperPath, target = batWrapperPath)
                span.addEvent("kotlin.bat script copy scheduled for after JVM shutdown")
            } else {
                copyAndReplaceSafely(source = newBatWrapperPath, target = batWrapperPath)
            }
        }
        terminal.success("Update successful")
    }

    private fun checkNotDirectories(vararg wrapperPaths: Path) {
        val clashingDirs = wrapperPaths.filter { it.exists() && it.isDirectory() }
        if (clashingDirs.isNotEmpty()) {
            userReadableError("Kotlin wrapper scripts cannot be updated because a directory with a conflicting name exists: " +
                    clashingDirs.first().normalize().absolutePathString()
            )
        }
    }

    private fun confirmCreateIfMissingWrappers(vararg wrapperPaths: Path) {
        val missingScripts = wrapperPaths.filterNot { it.exists() }
        if (missingScripts.isEmpty()) {
            return
        }
        val targetDirRef = targetDir.pathString.takeIf { it != "." } ?: "the current directory"
        val prompt = if (missingScripts.size == wrapperPaths.size) {
            "Kotlin wrappers were not found in $targetDirRef.\nWould you like to create them from scratch? (Y/n)"
        } else {
            "A Kotlin wrapper is missing: ${missingScripts.first().normalize().absolutePathString()}.\nUpdating will create it. Would you like to continue? (Y/n)"
        }
        val answer = terminal.prompt(
            prompt = prompt,
            default = "y",
            showChoices = false,
            showDefault = false,
            choices = listOf("y", "Y", "n", "N"),
        )
        if (answer?.lowercase() != "y") {
            terminal.println("Update aborted.")
            exitProcess(0)
        }
    }

    private suspend fun DesiredVersion.resolve() = when (this) {
        is DesiredVersion.Latest -> getLatestVersion(includeDevVersions = includeDevVersions)
        is DesiredVersion.SpecificVersion -> version
    }

    private suspend fun getLatestVersion(includeDevVersions: Boolean): String =
        spanBuilder("Fetch latest Kotlin Toolchain version").use {
            terminal.println("Fetching latest Kotlin Toolchain version info...")
            // TODO use the latest-version.txt file instead when we update it from our builds
            val metadataXml = fetchMavenMetadataXml()
            xmlVersionElementRegex.findAll(metadataXml)
                .mapNotNull { parseKotlinCliVersion(it.groupValues[1]) }
                .filter { !it.isDevVersion || (includeDevVersions && !it.isSpecialBranchVersion) }
                .maxByOrNull { ComparableVersion(it.fullMavenVersion) }
                ?.fullMavenVersion
                ?.also {
                    val versionMoniker = if (includeDevVersions) "dev version of the Kotlin Toolchain" else "Kotlin Toolchain version"
                    terminal.println("Latest $versionMoniker is ${terminal.theme.info(it)}")
                }
                ?: userReadableError("Couldn't read Kotlin Toolchain versions from maven-metadata.xml:\n\n$metadataXml")
        }

    private suspend fun fetchMavenMetadataXml(): String = try {
        amperHttpClient.get("$repository/org/jetbrains/kotlin/kotlin-cli/maven-metadata.xml").bodyAsText()
    } catch (e: Exception) {
        userReadableError("Couldn't fetch the latest Kotlin Toolchain version:\n$e")
    }

    private suspend fun downloadWrapper(version: String, extension: String): Path = try {
        spanBuilder("Download wrapper script (kotlin$extension)").use {
            val url = "$repository/org/jetbrains/kotlin/kotlin-cli/$version/kotlin-cli-$version-wrapper$extension"
            Downloader.downloadFileToCacheLocation(
                url = url,
                userCacheRoot = commonOptions.sharedCachesRoot,
                infoLog = false,
            )
        }
    } catch (e: Exception) {
        userReadableError("Couldn't fetch Kotlin wrapper script version $version:\n$e")
    }

    private suspend fun runKotlinToolchainVersionFirstRun(batWrapper: Path, bashWrapper: Path): ProcessResult {
        val command = when (OsFamily.current) {
            OsFamily.Windows -> if (runningWrapper.extension == "bat") {
                listOf(batWrapper.absolutePathString(), "--version")
            } else {
                // If we're running the bash script on Windows (probably with Git Bash), we need to also use the bash
                // script here. For this, we need to call bash explicitly.
                // Finding the corresponding unix-style path is not trivial, so we instead use ./name and ensure we're
                // in the correct working directory when running the process.
                listOf("bash.exe", "-c", ShellQuoting.quoteArgumentsPosixShellWay(listOf("./${bashWrapper.name}", "--version")))
            }
            OsFamily.Linux,
            OsFamily.MacOs,
            OsFamily.FreeBSD,
            OsFamily.Solaris -> listOf(bashWrapper.absolutePathString(), "--version")
        }
        // This working dir is intentional to support a plain `./kotlin` in Windows Git bash (without paths shenanigans)
        return runProcess(
            workingDir = bashWrapper.absolute().parent,
            command = command,
            outputMode = ProcessOutputMode.Inherit,
            input = ProcessInput.Inherit,
        )
    }

    private fun Path.setReadExecPermissions() {
        fileAttributesViewOrNull<PosixFileAttributeView>()
            ?.setPermissions(setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE))
            ?: run {
                val file = toFile()
                file.setReadable(true)
                file.setWritable(true, true)
                file.setExecutable(true)
            }
    }

    /**
     * Copies the given [source] file to the given [target], replacing the original file if present.
     * In case of errors, it the original [target] file is restored.
     * The original file is atomically moved to another path (then deleted) so the physical file can be preserved.
     * This means that if [target] points to a currently running bash script, the script execution will not be affected.
     */
    private fun copyAndReplaceSafely(source: Path, target: Path) {
        if (target.notExists()) {
            // We copy (not move) in case of concurrent updates all wanting to move the same file
            source.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        // Not in a temp dir on purpose. We want to guarantee being in the same drive to allow atomic moves.
        // The name is unique to avoid issues with concurrent updates.
        val oldFileTemp = createTempFile(target.parent, "${target.name}.old")
        try {
            // Renaming a running script allows the execution to continue on unix systems (same inode on disk)
            target.moveTo(oldFileTemp, StandardCopyOption.ATOMIC_MOVE)
            try {
                // We copy (not move) in case of concurrent updates all wanting to move the same file
                source.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // We restore the old file in case of problems.
                // This is non-atomic because we don't want to fail if the file exists
                // (there could have been a partial or concurrent copy)
                oldFileTemp.moveTo(target, overwrite = true)
                throw e
            } finally {
                oldFileTemp.deleteIfExists()
            }
        } catch (e: Exception) {
            userReadableError("Couldn't update Kotlin wrapper: $e", e)
        }
    }

    @OptIn(ProcessLeak::class)
    private fun copyAndReplaceLaterWindows(source: Path, target: Path) {
        // If some cleanup at the end of the update command takes unusually long, we don't want to risk a race and try
        // to replace kotlin.bat while it's still running. For this reason, we try to schedule this external process as
        // close as possible to the termination of the update command's JVM, which is why we do it in a shutdown hook.
        Runtime.getRuntime().addShutdownHook(Thread {
            startLongLivedProcess(
                // The 'ping' here is used to sleep 1 second.
                // The 'timeout' command only works in interactive consoles (thus fails in our case); 'ping' is reliable.
                // Using the IP instead of 'localhost' is a conscious choice, because localhost may involve DNS or hosts
                // file lookup, and also might be mapped to ::1 (IPv6) which might make ping fail or behave differently.
                command = listOf("cmd", "/c", "ping -n 2 127.0.0.1 & copy /y ${source.absolute().quotedForCmd()} ${target.absolute().quotedForCmd()}")
            )
        })
    }

    private fun Path.quotedForCmd(): String {
        // paths can't contain quotes on Windows
        return if (pathString.contains(' ')) "\"$pathString\"" else pathString
    }
}

private val xmlVersionElementRegex = Regex("<version>(.+?)</version>")

private data class KotlinToolchainVersion(
    val versionTriplet: String,
    val devBuildNumber: String?,
    val branchSuffix: String?,
    val fullMavenVersion: String,
) {
    val isDevVersion get() = devBuildNumber != null
    val isSpecialBranchVersion get() = branchSuffix != null
}

private val versionRegex = Regex("""(?<versionTriplet>[^-]+)(-dev-(?<build>\d+)(-(?<branchSuffix>.*))?)?""")

private fun parseKotlinCliVersion(version: String): KotlinToolchainVersion? {
    val versionMatch = versionRegex.matchEntire(version) ?: return null
    return KotlinToolchainVersion(
        versionTriplet = versionMatch.groups["versionTriplet"]?.value ?: error("versionTriplet is mandatory"),
        devBuildNumber = versionMatch.groups["build"]?.value,
        branchSuffix = versionMatch.groups["branchSuffix"]?.value,
        fullMavenVersion = version,
    )
}
