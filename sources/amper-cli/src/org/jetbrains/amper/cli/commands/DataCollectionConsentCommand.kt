/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.info
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.fus.consent.ConsentState
import org.jetbrains.amper.fus.consent.FusConsent

internal class DataCollectionConsentCommand : AmperSubcommand(name = "data-collection-consent") {

    init {
        subcommands(
            GiveConsentCommand(),
            RevokeConsentCommand(),
            ShowConsentCommand(),
        )
    }

    override fun help(context: Context): String = "Change your consent to usage statistics data collection"

    override suspend fun run() = Unit
}

private class GiveConsentCommand: AmperSubcommand(name = "give") {

    override fun help(context: Context): String = "Give your consent to data collection"

    override fun helpEpilog(context: Context): String =
        "Running this command is gives consent to data collection as an answer to the following question:\n\n" +
                FusConsent.PROMPT_BANNER_MARKDOWN_1_2

    override suspend fun run() {
        terminal.println(Markdown(FusConsent.PROMPT_BANNER_MARKDOWN_1_2))
        FusConsent.setState(ConsentState.Accepted)
        terminal.println()
        terminal.success("You have accepted these terms.")
        terminal.println()
        terminal.println("You can change your consent at any time by running `amper data-collection-consent revoke`.")
    }
}

private class RevokeConsentCommand: AmperSubcommand(name = "revoke") {

    override fun help(context: Context): String = "Revoke your consent to data collection"

    override suspend fun run() {
        FusConsent.setState(ConsentState.Rejected)
    }
}

private class ShowConsentCommand: AmperSubcommand(name = "show") {

    override fun help(context: Context): String = "Shows the current state of your consent regarding data collection"

    override suspend fun run() {
        terminal.println(Markdown(FusConsent.PROMPT_BANNER_MARKDOWN_1_2))
        terminal.println()
        val state = FusConsent.detect()
        when (state) {
            ConsentState.Accepted -> {
                terminal.success("You have accepted these terms.")
                terminal.println()
                terminal.println(Markdown("You can revoke your consent at any time by running `kotlin data-collection-consent revoke`."))
            }
            ConsentState.Rejected -> {
                terminal.warning("You have rejected these terms.")
                terminal.println()
                terminal.println(Markdown("You can change your mind at any time by running `kotlin data-collection-consent give`."))
            }
            null -> {
                terminal.info("You have not yet answered this question.")
            }
        }
    }
}
