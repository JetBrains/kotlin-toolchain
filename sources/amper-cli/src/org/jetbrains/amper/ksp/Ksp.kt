/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.ksp

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.lazyload.ExtraClasspath
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.majorVersion
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

internal class Ksp(
    val kspVersion: String,
    private val jdk: Jdk,
    private val kspImplJars: List<Path>,
    private val processRunner: ProcessRunner,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(Ksp::class.java)
    }

    /**
     * Run KSP.
     */
    suspend fun run(
        compilationType: KspCompilationType,
        processorClasspath: List<Path>,
        config: KspConfig,
        tempRoot: AmperProjectTempRoot,
    ) {
        val workingDir = config.projectBaseDir

        // TODO stop doing that when KSP 1.0.26 is released and our default version is bumped
        val legacyListMode = KspConfig.needsLegacyListMode(kspVersion)
        val processorClasspathStr = if (legacyListMode) {
            // We relativize paths to avoid issues with absolute windows paths split on ':'
            // See: https://github.com/google/ksp/issues/2046
            processorClasspath.joinToString(":") { it.relativeTo(workingDir).pathString }
        } else {
            processorClasspath.joinToString(File.pathSeparator)
        }
        val args = config.toCommandLineOptions(workingDir, legacyListMode) + processorClasspathStr

        logger.debug("ksp {} {}", compilationType, args)
        val result = processRunner.runJava(
            jdk = jdk,
            workingDir = workingDir,
            mainClass = "org.jetbrains.amper.ksp.launcher.KspLauncher",
            classpath = ExtraClasspath.KSP_LAUNCHER.findJarsInDistribution() + kspImplJars,
            programArgs = [compilationType.kspMainClassFqn] + args,
            argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
            outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger, prefix = "[ksp] ")),
            // KSP uses some Unsafe APIs inside (because it depends on AA, thus IntelliJ)
            // Since Java 25, we need to explicitly allow them.
            // They should eventually fix the actual usage inside. This is tracked over there:
            // https://github.com/google/ksp/issues/2753
            jvmArgs = buildList {
                if (jdk.majorVersion >= 25) {
                    add("--enable-native-access=ALL-UNNAMED")
                    add("--sun-misc-unsafe-memory-access=allow")
                }
            },
        )
        // Note: KSP fails automatically with exit code 1 if any error log is present
        if (result.exitCode != 0) {
            userReadableError("KSP execution failed with exit code ${result.exitCode} (see errors above)")
        }
    }
}
