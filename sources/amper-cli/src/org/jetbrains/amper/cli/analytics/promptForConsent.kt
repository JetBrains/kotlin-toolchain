/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.fus.consent.ConsentState
import org.jetbrains.amper.fus.consent.FusConsent

internal fun Terminal.promptForConsent(): ConsentState? {
    if (!terminalInfo.interactive) {
        // if the terminal is not interactive, we can't prompt for consent
        return null
    }
    println(Markdown(FusConsent.PROMPT_BANNER_MARKDOWN_1_2))
    val result = interactiveSelectList(
        items = [ConsentState.Accepted, ConsentState.Rejected],
        nameSelector = {
            when (it) {
                ConsentState.Accepted -> "Send data"
                ConsentState.Rejected -> "Don't send data"
            }
        },
        preSelectedItem = ConsentState.Rejected,
        filterable = false,
    ) ?: throw PrintMessage("Operation aborted")
    return result
}