/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalWasmJsInterop::class)

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test

@OptIn(ExperimentalStdlibApi::class)
@Suppress("DEPRECATION")
@EagerInitialization
private val scheduleJsError: JsAny = throwError()

private fun throwError(): JsAny = js("{ throw new Error('Intentional page error from the test module') }")

class PageErrorTest {
    @Test
    fun testTriggeringAPageError() {
        // do nothing
    }
}
