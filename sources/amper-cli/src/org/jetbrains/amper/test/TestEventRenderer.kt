/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.jetbrains.amper.testevents.TestEvent

/**
 * A CLI-side presentation of Kotlin Toolchain test events.
 */
internal interface TestEventRenderer {
    fun render(event: TestEvent)
}
