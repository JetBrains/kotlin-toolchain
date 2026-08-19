/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.rendering.Lines
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.rendering.WidthRange
import com.github.ajalt.mordant.terminal.Terminal

private class StyledWidget(
    private val content: Widget,
    private val style: TextStyle,
) : Widget {
    override fun measure(t: Terminal, width: Int): WidthRange = content.measure(t, width)
    override fun render(t: Terminal, width: Int): Lines = content.render(t, width).withStyle(style)
}

/**
 * Use to specify a super-style for the widget.
 * The given [style] will be merged with the styles of the underlying content where applicable.
 */
fun Widget.withStyle(
    style: TextStyle,
): Widget = StyledWidget(this, style)
