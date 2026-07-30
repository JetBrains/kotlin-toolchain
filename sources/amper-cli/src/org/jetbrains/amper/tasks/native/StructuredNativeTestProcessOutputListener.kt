/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.util.TeamCityMessageProcessor

/**
 * Translates the TeamCity service messages emitted by the Kotlin/Native test runners to Kotlin Toolchain test events.
 *
 * Supports both flow-based reporting (e.g. TestBalloon one) and non-flow-based approximation based on the assumption
 * that all tests run sequentially.
 */
internal class StructuredNativeTestProcessOutputListener(
    private val teamCityMessageProcessor: TeamCityMessageProcessor,
) : ProcessOutputListener {

    override fun onStdoutLine(line: String, pid: Long) = teamCityMessageProcessor.parse(line, stderr = false)

    override fun onStderrLine(line: String, pid: Long) = teamCityMessageProcessor.parse(line, stderr = true)
}
