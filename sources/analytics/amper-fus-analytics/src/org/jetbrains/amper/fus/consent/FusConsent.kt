/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.consent

import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo

/**
 * Gives access to the state of the Feature Usage Statistics (FUS) collection consent given by the user.
 * The state is shared between all JetBrains products.
 */
object FusConsent {

    private const val BANNER_VERSION = "1.2"

    /**
     * The versions of the consent flags that we can reuse for our event data collection.
     *
     * We can reuse existing FUS flags of our banner, but also version 1.1 currently in use in our IDEs.
     */
    private val USABLE_CONSENT_VERSIONS = setOf(BANNER_VERSION, "1.1")

    /**
     * The text (in Markdown format) to show when prompting the user for consent.
     * It should be followed by an interactive selection to send data or not.
     */
    const val PROMPT_BANNER_MARKDOWN_1_2 = """
        JetBrains would like to collect basic telemetry and events data about your product use 
        such as commands, used models or device values to improve our products.
    
        Data sharing preferences apply to all JetBrains products.
        
        Learn more in our [Product Data Collection and Usage Notice](https://www.jetbrains.com/legal/docs/terms/product_data_collection/).
    """.trimIndent()


    /**
     * Detects existing FUS collection consent, and calls [prompt] if no consent was found.
     * If a new consent state is returned by [prompt], it is persisted.
     */
    fun detect(
        os: OsFamily = SystemInfo.CurrentHost.family,
        prompt: () -> ConsentState? = { null },
    ): ConsentState? {
        val jetBrainsConsent = JetBrainsConsent(os)
        val fusFlag = jetBrainsConsent.readFlag(ConsentFlag.FUS_ID)
        if (fusFlag != null && fusFlag.version in USABLE_CONSENT_VERSIONS) {
            return fusFlag.state
        }
        val newAnswer = prompt() ?: return null
        jetBrainsConsent.writeFlag(ConsentFlag.FUS_ID, consentBannerVersion = BANNER_VERSION, state = newAnswer)
        return newAnswer
    }
}
