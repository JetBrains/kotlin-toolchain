/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

import org.jetbrains.amper.test.golden.TemplatingGoldenFormatter.Variable
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString

/**
 * Shortcut for tests that have very simple [inputContent].
 */
fun GoldFileTest(caseName: String, base: Path, inputContent: GoldFileTest.() -> String) =
    object : GoldFileTest(caseName, base) {
        override fun getInputContent() = inputContent()
    }

/**
 * Represents a single test (usually a single test method) that
 * retrieves expected content only from the specified gold file.
 *
 * Assertion is made by comparing the actual content with the expected one and generating
 * special `.tmp` file with the actual content in case of mismatch.
 *
 * Also, the expected content is normalized first and some placeholders are interpolated.
 *
 * @param caseName The name of the test case.
 * @param goldenFilesDir Base directory for the golden files.
 */
abstract class GoldFileTest(
    val caseName: String,
    goldenFilesDir: Path,
) {
    /**
     * Absolute path to the base directory for the golden files.
     */
    protected val goldenFilesDir: Path = goldenFilesDir.absolute()

    protected open val expectPostfix: String = ".result.txt"

    /**
     * Get the actual content to be tested.
     */
    abstract fun getInputContent(): String

    // The gold files are also the input YAML files for the tests, and we want to test our code with real life system
    // line endings.
    @OptIn(SystemDependentGoldenFormatter::class)
    open fun doTest() {
        assertContentEqualsGoldenFile(
            goldenFile = goldenFilesDir.resolve(caseName + expectPostfix),
            actualContent = getInputContent(),
            goldenFormatter = SystemLineSeparatorGoldenFormatter + TemplatingGoldenFormatter(variables = [
                Variable(placeholder = "{{ testResources }}", value = goldenFilesDir.normalize().absolutePathString()),
                Variable(placeholder = "{{ fileSeparator }}", value = File.separator),
            ]),
        )
    }
}
