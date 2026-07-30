/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal

internal fun Terminal.reportXcodeError(line: String) {
    this.println("error: $line")
}

internal fun Terminal.reportXcodeWarning(line: String) {
    this.println("warning: $line")
}
