/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlin.test.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuiteFailureTest {
    @BeforeAll
    fun setUp() {
        error("Suite setup failed")
    }

    @Test
    fun neverRuns() = Unit
}
