/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The name of the environment variable that disables agent detection when set to `false`.
 * This is meant for internal use, such as tests that need to run as if no agent was present.
 */
internal const val AGENT_DETECTION_ENABLED_VARIABLE: String = "KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED"

/**
 * The parts of the runtime environment that agent detection relies on.
 *
 * This is an interface so that detection can be tested without touching the real environment.
 */
internal interface AgentDetectionEnvironment {

    /**
     * Returns the value of the environment variable [name], or null if it is not set.
     */
    fun getEnv(name: String): String?

    /**
     * Returns true if a file or directory exists at [path].
     */
    fun fileExists(path: Path): Boolean

    /**
     * Returns true if the standard output of this process is attached to an interactive terminal.
     */
    fun isStdoutTerminal(): Boolean
}

/**
 * The real environment of the current process.
 */
internal object SystemAgentDetectionEnvironment : AgentDetectionEnvironment {
    override fun getEnv(name: String): String? = System.getenv(name)
    override fun fileExists(path: Path): Boolean = path.exists()
    override fun isStdoutTerminal(): Boolean = System.console()?.isTerminal == true
}

/**
 * Returns true if detection was disabled explicitly with the [AGENT_DETECTION_ENABLED_VARIABLE].
 */
internal fun isDetectionDisabled(environment: AgentDetectionEnvironment): Boolean =
    environment.getEnv(AGENT_DETECTION_ENABLED_VARIABLE)?.trim().equals("false", ignoreCase = true)
