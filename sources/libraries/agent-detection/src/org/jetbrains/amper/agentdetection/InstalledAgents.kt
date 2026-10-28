/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Detects which of the known agents seem to be installed on this machine.
 *
 * This is a heuristic: an agent is considered installed if its executable is found on the `PATH`.
 * It can have false negatives (agents installed outside the `PATH`) and false positives (unrelated
 * executables with the same name).
 *
 * Nothing is detected if detection is disabled explicitly (see [isDetectionDisabled]).
 *
 * For detecting the agent that initiated current CLI run, see [detectAgent].
 */
fun detectInstalledAgents(): Set<Agent> = detectInstalledAgents(SystemAgentDetectionEnvironment)

internal fun detectInstalledAgents(environment: AgentDetectionEnvironment): Set<Agent> {
    if (isDetectionDisabled(environment)) return []
    val executableFinder = ExecutableFinder(environment)
    return Agent.entries.filterTo(mutableSetOf()) {
        it.executable != null && executableFinder.isOnPath(it.executable)
    }
}

/**
 * Finds executables on the `PATH` of the given [environment], taking into account the `PATHEXT` extensions on Windows.
 */
private class ExecutableFinder(private val environment: AgentDetectionEnvironment) {
    private val pathDirectories: List<Path> =
        environment.getEnv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.mapNotNull { it.toPathOrNull() }

    private val extensions: List<String> =
        [""] + environment.getEnv("PATHEXT").orEmpty().split(';').filter { it.isNotBlank() }.map { it.lowercase() }

    fun isOnPath(name: String): Boolean = pathDirectories.any { directory ->
        extensions.any { environment.fileExists(directory.resolve(name + it)) }
    }
}

private fun String.toPathOrNull(): Path? = try {
    Path(this)
} catch (_: InvalidPathException) {
    null
}
