/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteFailed
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted

/**
 * Renders Kotlin Toolchain test events for local CLI use.
 *
 * @param isVerbose `true` renders all the test events, `false` only renders failures, aborts, and skips.
 */
internal class PrettyRenderer(
    private val terminal: Terminal,
    private val isVerbose: Boolean = !terminal.terminalInfo.outputInteractive,
) : EventSink<TestEvent> {
    private val descriptors = mutableMapOf<TestId, TestDescriptor>()

    override fun emit(event: TestEvent) = render(event)

    private fun render(event: TestEvent) {
        when (event) {
            is TestSuiteStarted -> {
                descriptors[event.descriptor.id] = event.descriptor
                if (isVerbose) {
                    print(PrettyTestEvent.ContainerStarted, event.descriptor.displayName)
                }
            }
            is TestSuiteFinished -> if (isVerbose) {
                descriptors[event.testId]?.let { print(PrettyTestEvent.ContainerFinished, it.displayName) }
            }
            is TestSuiteAborted -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Aborted, it.displayName)
                detail(PrettyTestEvent.Aborted.style, "Reason", event.abortMessage)
            }
            is TestSuiteFailed -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Failed, it.displayName)
                detail(PrettyTestEvent.Failed.style, "Exception", event.stackTrace ?: event.failureMessage)
            }
            is TestSuiteSkipped -> {
                print(PrettyTestEvent.Skipped, event.descriptor.displayName)
                detail(PrettyTestEvent.Skipped.style, "Reason", event.reason)
            }
            is TestStarted -> {
                descriptors[event.descriptor.id] = event.descriptor
                if (isVerbose) {
                    print(PrettyTestEvent.TestStarted, event.descriptor.displayName)
                }
            }
            is TestStdoutEvent -> if (isVerbose) {
                terminal.rawPrint(event.text)
            }
            is TestStderrEvent -> if (isVerbose) {
                terminal.rawPrint(event.text, stderr = true)
            }
            is TestFinished.Succeeded -> if (isVerbose) {
                descriptors[event.testId]?.let { print(PrettyTestEvent.Succeeded, it.displayName) }
            }
            is TestFinished.Aborted -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Aborted, it.displayName)
                detail(PrettyTestEvent.Aborted.style, "Reason", event.abortMessage)
            }
            is TestSkipped -> {
                print(PrettyTestEvent.Skipped, event.descriptor.displayName)
                detail(PrettyTestEvent.Skipped.style, "Reason", event.reason)
            }
            is TestFinished.Failed -> descriptors[event.testId]?.let {
                print(PrettyTestEvent.Failed, it.displayName)
                detail(PrettyTestEvent.Failed.style, "Exception", event.stackTrace ?: event.failureMessage)
            }
            is TestReportEvent -> if (isVerbose) {
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
    Aborted("Aborted", Theme.Default.muted),
    Failed("Failed", Theme.Default.danger),
    Succeeded("Passed", Theme.Default.success),
    Reported("Reported"),
    ;

    companion object {
        val columnWidth: Int = entries.maxOf { it.displayName.length }
        val indent: String = " ".repeat(columnWidth + 2)
    }
}
