/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.klibsio

import org.jetbrains.amper.cli.UserReadableError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotifyKlibsIoTest {

    @Test
    fun `a notification failure propagates as a user error when failOnError is true`() {
        val error = assertFailsWith<UserReadableError> {
            reportNotificationFailure(failOnError = true, message = "boom", cause = RuntimeException("boom"))
        }
        assertEquals("boom", error.message)
        assertEquals(1, error.exitCode)
    }

    @Test
    fun `a notification failure is swallowed when failOnError is false`() {
        reportNotificationFailure(failOnError = false, message = "boom", cause = RuntimeException("boom"))
    }
}
