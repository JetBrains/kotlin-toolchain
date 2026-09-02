/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compilerplugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.test.Test

class ParcelizeTest : CliTestBase() {

    @Test
    fun `parcelize android lib - build`() = runSlowTest {
        runCli(testProject("parcelize-android-lib"), "build", configureAndroidHome = true)
    }

    @Test
    fun `parcelize android lib - test`() = runSlowTest {
        runCli(testProject("parcelize-android-lib"), "test", configureAndroidHome = true)
    }

    @Test
    fun `parcelize android app - build`() = runSlowTest {
        runCli(testProject("parcelize-android-app"), "build", configureAndroidHome = true)
    }

    @Test
    fun `parcelize with shared kmp model`() = runSlowTest {
        runCli(testProject("parcelize-shared-kmp-model"), "build", configureAndroidHome = true)
    }
}
