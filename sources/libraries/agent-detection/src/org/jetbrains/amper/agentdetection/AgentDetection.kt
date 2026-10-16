/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The name of the environment variable that any tool can set to identify itself as an AI agent.
 * See the [AI_AGENT proposal](https://github.com/vercel/detect-agent#the-ai_agent-standard).
 */
private const val AI_AGENT_VARIABLE: String = "AI_AGENT"

/**
 * The name of the environment variable that disables agent detection when set to `false`.
 * This is meant for internal use, such as tests that need to run as if no agent was present.
 */
private const val AGENT_DETECTION_ENABLED_VARIABLE: String = "KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED"

/**
 * The parts of the runtime environment that agent detection relies on.
 *
 * This is an interface so that detection can be tested without touching the real environment.
 */
internal interface AgentDetectionEnvironment {

    /** Returns the value of the environment variable [name], or null if it is not set. */
    fun getEnv(name: String): String?

    /** Returns true if a file or directory exists at [path]. */
    fun fileExists(path: Path): Boolean

    /** Returns true if the standard output of this process is attached to an interactive terminal. */
    fun isStdoutTerminal(): Boolean
}

/**
 * The real environment of the current process.
 */
private object SystemAgentDetectionEnvironment : AgentDetectionEnvironment {
    override fun getEnv(name: String): String? = System.getenv(name)
    override fun fileExists(path: Path): Boolean = path.exists()
    override fun isStdoutTerminal(): Boolean = System.console()?.isTerminal == true
}

/**
 * Returns true if this process seems to be run by an AI agent. See [detectAgent] for details.
 */
fun isRunByAgent(): Boolean = detectAgent() != null

/**
 * Detects which AI agent seems to be running this process, or returns null if none is detected.
 *
 * This is a conservative heuristic based on environment markers set by the known agents, so it can have false
 * negatives (unknown agents, agents that change their markers, agents running on CI without [AI_AGENT_VARIABLE]) and,
 * rarely, false positives (markers inherited by unrelated processes). Prefer asking agents to set
 * [AI_AGENT_VARIABLE] for reliable detection.
 *
 * Detection steps, in order:
 * 0. If the [AGENT_DETECTION_ENABLED_VARIABLE] environment variable is `false`, nothing is detected.
 * 1. If the [AI_AGENT_VARIABLE] environment variable is non-blank and not a falsy value (`false`, `0`, `no`,
 *    `off`, `none`), an agent is detected: the matching known agent, or [Agent.Other] if the name is not known.
 *    This explicit declaration is honored even on CI.
 * 2. If the process runs on a CI server (see [isRunningOnCi]), nothing is detected: some markers of the known agents
 *    (such as `COPILOT_GITHUB_TOKEN`) are routinely configured on CI for unrelated reasons.
 * 3. Otherwise, the first known agent whose markers are set is detected.
 *
 * This is a port of [vercel/detect-agent](https://github.com/vercel/detect-agent).
 */
fun detectAgent(): Agent? = detectAgent(SystemAgentDetectionEnvironment)

internal fun detectAgent(environment: AgentDetectionEnvironment): Agent? {
    if (environment.getEnv(AGENT_DETECTION_ENABLED_VARIABLE)?.trim().equals("false", ignoreCase = true)) return null

    val declaredAgent = environment.getEnv(AI_AGENT_VARIABLE)?.trim()
    if (!declaredAgent.isNullOrEmpty() && declaredAgent.lowercase() !in FalsyValues) {
        return agentByDeclaredName(declaredAgent)
    }

    if (isRunningOnCi(environment)) return null

    return environment.detectAgentByMarkers()
}

private val FalsyValues = setOf("false", "0", "no", "off", "none")

/** Environment variables that are set (to any non-empty, non-falsy value) by the well-known CI servers. */
private val CiVariables = [
    "CI",
    "TEAMCITY_VERSION",
    "JENKINS_URL",
    "TF_BUILD",
    "CODEBUILD_BUILD_ID",
]

/**
 * Returns true if this process seems to run on a CI server, based on the environment variables that well-known CI
 * servers set. A falsy value (such as `CI=false`) doesn't count.
 */
internal fun isRunningOnCi(environment: AgentDetectionEnvironment): Boolean = CiVariables.any { name ->
    environment.getEnv(name)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase().let { it != null && it !in FalsyValues }
}
