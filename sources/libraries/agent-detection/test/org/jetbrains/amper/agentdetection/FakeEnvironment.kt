/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.nio.file.Path

internal class FakeEnvironment(
    val env: Map<String, String> = emptyMap(),
    val existingFiles: Set<Path> = [],
    val stdoutIsTerminal: Boolean = true,
) : AgentDetectionEnvironment {
    override fun getEnv(name: String): String? = env[name]
    override fun fileExists(path: Path): Boolean = path in existingFiles
    override fun isStdoutTerminal(): Boolean = stdoutIsTerminal
}
