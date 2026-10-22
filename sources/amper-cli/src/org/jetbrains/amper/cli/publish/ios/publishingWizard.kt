/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.publish.ios

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.ConversionResult
import com.github.ajalt.mordant.terminal.Prompt
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import org.jetbrains.amper.cli.terminal.printCompletedMilestone
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.operationEventScope
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

context(terminal: Terminal)
internal inline fun <R> setupMilestone(
    milestone: String,
    block: () -> R,
): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

    return block().also {
        terminal.printCompletedMilestone(milestone)
    }
}

context(_: OperationEventSink, terminal: Terminal)
internal suspend fun <T> setupRequiresInput(
    title: String,
    instructions: String,
    fallback: String,
    block: suspend WizardStep.() -> T,
): T {
    val titleStyle = TextStyle(
        underline = true,
        bold = true,
    ) + terminal.theme.warning
    terminal.println(titleStyle(title))
    terminal.println()
    terminal.println(Markdown(instructions))
    val step = WizardStep(terminal, fallback)
    if (!terminal.terminalInfo.interactive) step.stop()
    return operationEventScope(title, isInteractive = true) {
        step.block()
    }
}

internal class WizardStep(
    private val terminal: Terminal,
    private val fallback: String,
) {
    fun message(markdown: String) = terminal.println(Markdown(markdown))

    fun stop(
        adviseToRerun: Boolean = true,
    ): Nothing = userReadableError {
        appendLine(fallback)
        if (adviseToRerun) append("Rerun this command later when you are ready to proceed.")
    }

    fun ask(label: String): String {
        val answer = terminal.prompt(
            prompt = label + terminal.theme.muted(" (or `${YesNoQuitChoice.Quit}` to exit the setup)"),
            convert = { input ->
                val value = input.trim()
                if (value.isEmpty()) {
                    ConversionResult.Invalid("Enter a value, or ${YesNoQuitChoice.Quit} to exit the setup.")
                } else {
                    ConversionResult.Valid(value)
                }
            }
        ) ?: stop()
        if (answer.lowercase() in YesNoQuitChoice.Quit.acceptableStrings) stop()
        return answer
    }

    fun confirmOrQuit(
        label: String,
        default: Boolean? = true,
    ) {
        val answer = ChoicePrompt(
            terminal = terminal,
            prompt = label,
            choices = ConfirmQuitChoice.entries,
            default = when (default) {
                true -> ConfirmQuitChoice.Confirm
                false -> ConfirmQuitChoice.Quit
                else -> null
            },
        ).ask() ?: ConfirmQuitChoice.Quit
        return when (answer) {
            ConfirmQuitChoice.Confirm -> Unit
            ConfirmQuitChoice.Quit -> stop(adviseToRerun = false)
        }
    }

    fun yesNoQuit(
        label: String,
        default: Boolean? = null,
    ): Boolean {
        val answer = ChoicePrompt(
            terminal = terminal,
            prompt = label,
            choices = YesNoQuitChoice.entries,
            default = when (default) {
                true -> YesNoQuitChoice.Yes
                false -> YesNoQuitChoice.No
                else -> null
            },
        ).ask() ?: YesNoQuitChoice.Quit
        return when (answer) {
            YesNoQuitChoice.Yes -> true
            YesNoQuitChoice.No -> false
            YesNoQuitChoice.Quit -> stop()
        }
    }
}

private class ChoicePrompt<T : Choice>(
    terminal: Terminal,
    prompt: String,
    choices: Collection<T>,
    default: T?,
) : Prompt<T>(
    terminal = terminal,
    prompt = prompt,
    choices = choices,
    default = default,
) {
    override fun makePromptDefault(default: T): String {
        return "(default: ${default.long})"
    }

    override fun convert(input: String): ConversionResult<T> {
        val trimmed = input.trim().lowercase()
        return choices.find { trimmed in it.acceptableStrings }
            ?.let(ConversionResult<T>::Valid)
            ?: ConversionResult.Invalid("Invalid choice, try again")
    }
}

private abstract class Choice(
    val short: String,
    val long: String,
) {
    override fun toString() = "$short/$long"
}

private val Choice.acceptableStrings: Set<String> get() = [short, long]

private sealed class YesNoQuitChoice(
    short: String, long: String,
) : Choice(short, long) {
    object Yes : YesNoQuitChoice("y", "yes")
    object No : YesNoQuitChoice("n", "no")
    object Quit : YesNoQuitChoice("q", "quit")

    companion object {
        val entries get() = [Yes, No, Quit]
    }
}

private sealed class ConfirmQuitChoice(
    short: String, long: String,
) : Choice(short, long) {
    object Confirm : ConfirmQuitChoice("c", "confirm")
    object Quit : ConfirmQuitChoice("q", "quit")

    companion object {
        val entries get() = [Confirm, Quit]
    }
}

