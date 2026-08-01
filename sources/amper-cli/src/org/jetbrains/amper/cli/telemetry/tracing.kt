/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.telemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import org.jetbrains.amper.cli.terminal.filterAnsiCodes
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.telemetry.setListAttribute

fun SpanBuilder.setAmperModule(module: AmperModule): SpanBuilder =
    setAttribute("amper-module", module.userReadableName)

fun SpanBuilder.setFragments(fragments: List<Fragment>) =
    setListAttribute("fragments", fragments.map { it.name }.sorted())

/**
 * Sets attributes on this [Span] describing the given [result].
 */
fun Span.setProcessResultAttributes(result: ProcessResult) {
    setAttribute("exit-code", result.exitCode.toLong())
}
