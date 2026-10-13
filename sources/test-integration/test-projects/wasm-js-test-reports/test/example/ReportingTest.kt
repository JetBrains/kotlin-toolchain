/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.fail

class ReportingTest {
    @Test
    fun passes() {
        println("OUTPUT <&> '\" |[]")
    }

    @Test
    fun synchronousFailure() {
        fail("SYNC <&> '\" |[]\nsecond line")
    }

    @Test
    fun asynchronousPass() = runTest {
        withContext(Dispatchers.Default) { delay(400) }
    }

    @Test
    fun asynchronousFailure() = runTest {
        withContext(Dispatchers.Default) { delay(400) }
        fail("ASYNC <&> '\" |[]\nsecond line")
    }

    @Ignore
    @Test
    fun ignored() {
        fail("This test must not run")
    }
}

class TimeoutTest {
    @Test
    fun neverCompletes() = runTest {
        awaitCancellation()
    }
}
