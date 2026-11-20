/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.test.assertEqualsIgnoreLineSeparator
import org.jetbrains.amper.test.normalizeLineSeparators
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
 * Also, expected content is normalized first and some interpolations are made.
 * See [readNormalizedExpectedSafelyAndInterpolate] for details.
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

    private fun Path.readNormalizedExpectedSafelyAndInterpolate(): String {
        // This is the actual check.
        if (!exists()) writeText("")
        return readText()
            .normalizeLineSeparators()
            .trimTrailingWhitespacesAndEmptyLines()
            .replace("{{ testResources }}", goldenFilesDir.normalize().absolutePathString())
            .replace("{{ fileSeparator }}", File.separator)
            .replace("#kotlinVersion", DefaultVersions.kotlin)
            .replace("#composeDefaultVersion", DefaultVersions.compose)
    }

    open fun doTest() {
        val inputContent = getInputContent()
        val expectedPath = goldenFilesDir.resolve(caseName + expectPostfix).absolute()
        val expectContent = expectedPath.readNormalizedExpectedSafelyAndInterpolate()
        return assertEqualsIgnoreLineSeparator(
            expectContent,
            inputContent,
            expectedPath,
        )
    }
}
