/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.helpers

import org.jetbrains.amper.test.TempDirExtension
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path

abstract class FrontendTestCaseBase(
    
    /**
     * Common base path to the golden files' location.
     */
    val base: Path,
) {
    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    /**
     * The path that is treated as the root of the test project.
     */
    val testProjectDir: Path get() = tempDirExtension.path
}