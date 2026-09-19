/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginApiRepositoryTest {

    @Test
    fun `a distribution served from another repository takes the plugin api from there`() {
        assertEquals(
            expected = "https://example.com/maven",
            actual = pluginApiRepositoryUrl("https://example.com/maven"),
        )
    }

    @Test
    fun `a trailing slash is not part of the repository url`() {
        // the resolver appends the group path to the url, so a trailing slash would double the separator
        assertEquals(
            expected = "https://example.com/maven",
            actual = pluginApiRepositoryUrl("https://example.com/maven/"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            expected = "https://example.com/maven",
            actual = pluginApiRepositoryUrl("  https://example.com/maven  "),
        )
    }

    @Test
    fun `a distribution started without a wrapper takes the plugin api from the default repository`() {
        assertEquals(DefaultAmperRepositoryUrl, pluginApiRepositoryUrl(downloadRoot = null))
        assertEquals(DefaultAmperRepositoryUrl, pluginApiRepositoryUrl(downloadRoot = ""))
        assertEquals(DefaultAmperRepositoryUrl, pluginApiRepositoryUrl(downloadRoot = "   "))
    }

    @Test
    fun `naming the default repository changes nothing`() {
        assertEquals(DefaultAmperRepositoryUrl, pluginApiRepositoryUrl(DefaultAmperRepositoryUrl))
        assertEquals(DefaultAmperRepositoryUrl, pluginApiRepositoryUrl("$DefaultAmperRepositoryUrl/"))
    }
}
