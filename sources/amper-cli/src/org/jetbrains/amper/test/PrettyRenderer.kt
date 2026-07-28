/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteStarted

/**
 * Renders Kotlin Toolchain test events for local CLI use.
 */
internal class PrettyRenderer(
    private val terminal: Terminal,
) : TestEventRenderer {
    private val descriptors = mutableMapOf<TestId, TestDescriptor>()

    override fun render(event: TestEvent) {
        when (event) {
            is TestSuiteStarted -> {
                descriptors[event.descriptor.id] = event.descriptor
                print(PrettyTestEvent.ContainerStarted, event.descriptor.displayName)
            }
            is TestSuiteFinished -> descriptors[event.testId]?.let { print(PrettyTestEvent.ContainerFinished, it.displayName) }
            is TestStarted -> {
                descriptors[event.descriptor.id] = event.descriptor
                print(PrettyTestEvent.TestStarted, event.descriptor.displayName)
            }
            is TestStdoutEvent -> {
                // TODO: There is an issue with the animation widget that always appends new line to every print
                //  request. For this reason, we ignore \n events in interactive terminal because they produce
                //  extra blank lines between test messages.
                if (event.text == System.lineSeparator() && terminal.terminalInfo.interactive) return
                terminal.rawPrint(event.text)
            }
            is TestStderrEvent -> {
                // TODO: There is an issue with the animation widget that always appends new line to every print
                //  request. For this reason, we ignore \n events in interactive terminal because they produce
                //  extra blank lines between test messages.
                if (event.text == System.lineSeparator() && terminal.terminalInfo.interactive) return
                terminal.rawPrint(event.text, stderr = true)
            }
            is TestFinished.Succeeded -> descriptors[event.testId]?.let { print(PrettyTestEvent.Succeeded, it.displayName) }
            is TestFinished.Skipped -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Skipped, it.displayName)
                detail(PrettyTestEvent.Skipped.style, "Reason", event.description)
            }
            is TestFinished.Failed -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Failed, it.displayName)
                detail(PrettyTestEvent.Failed.style, "Exception", event.stackTrace ?: event.failureMessage)
            }
            is TestReportEvent -> {
                print(PrettyTestEvent.Reported, descriptors[event.testId]?.displayName ?: event.testId.value)
                detail(PrettyTestEvent.Reported.style, event.key, event.value)
            }
        }
    }

    private fun print(event: PrettyTestEvent, name: String) =
        println(event.style, "${bold(event.displayName).padEnd(PrettyTestEvent.columnWidth)} $name")

    private fun detail(style: TextStyle?, label: String, value: String) =
        println(style, "${PrettyTestEvent.indent}=> $label: ${value.prependIndent(PrettyTestEvent.indent).trim()}")

    private fun println(style: TextStyle?, message: String) = terminal.println(style?.invoke(message) ?: message)
}

private enum class PrettyTestEvent(
    val displayName: String,
    val style: TextStyle? = null,
) {
    ContainerStarted("Started", Theme.Default.info),
    ContainerFinished("Completed", Theme.Default.info),
    TestStarted("Started"),
    Skipped("Skipped", Theme.Default.muted),
    Failed("Failed", Theme.Default.danger),
    Succeeded("Passed", Theme.Default.success),
    Reported("Reported"),
    ;

    companion object {
        val columnWidth: Int = entries.maxOf { it.displayName.length }
        val indent: String = " ".repeat(columnWidth + 2)
    }
}
