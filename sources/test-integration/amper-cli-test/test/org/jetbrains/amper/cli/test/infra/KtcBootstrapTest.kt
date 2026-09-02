/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.runTestWithMdc
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class KtcBootstrapTest : CliTestBase() {

    @Test
    fun `kotlin toolchain can build itself`() = runTestWithMdc(timeout = 30.minutes) {
        runCli(
            projectDir = Dirs.amperCheckoutRoot,
            "build",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
    }

    @Test
    fun `kotlin toolchain can launch its own tests`() = runTestWithMdc(timeout = 30.minutes) {
        // We want to run some tests because it can discover problems with the test mechanism/classpath etc.
        // We cannot run all tests because that would amount to running the whole test suite twice.
        // All other tests will be covered anyway in the build of the MR that bumps the KTC version in the project.
        runCli(
            projectDir = Dirs.amperCheckoutRoot,
            "test",
            "-m",
            "schema",
            "--include-classes=org.jetbrains.amper.frontend.schema.ParserKtTest",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
    }
}
