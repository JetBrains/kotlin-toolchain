/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

import org.jetbrains.amper.test.generateUnifiedDiff
import org.junit.jupiter.api.AssertionFailureBuilder
import org.junit.jupiter.api.fail
import org.opentest4j.FileInfo
import java.nio.file.Path
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Asserts that [actualContent] matches the content of the given [goldenFile], assuming UTF-8 encoding.
 *
 * In case of mismatch, the actual content is written to a temporary file next to the golden file (with `.tmp` as
 * extension), so it can be used to update the golden file.
 *
 * If the golden file contents is in a different format than what needs to be used for comparison, use [goldenFormatter]
 * to transform the contents of the golden file to the comparable contents, and vice versa.
 *
 * The default [goldenFormatter] applies no transformation. Line endings and blank lines should properly match.
 * Prefer using proper .gitattributes over dynamic transformations to normalize line endings.
 */
fun assertContentEqualsGoldenFile(
    goldenFile: Path,
    actualContent: String,
    goldenFormatter: GoldenFormatter = IdentityGoldenFormatter,
    message: String = "Comparison failed",
) {
    val tmpActualFile = goldenFile.resolveSibling(goldenFile.name + ".tmp")
    if (!goldenFile.exists()) {
        tmpActualFile.writeText(goldenFormatter.toGolden(actualContent))
        fail("Reference golden file does not exist: ${goldenFile.absolutePathString()}\n" +
                "Remove the .tmp extension of the generated file to use it as a new golden file.")
    }

    if (goldenFormatter.fromGolden(goldenFile.readText()) != actualContent) {
        tmpActualFile.writeText(goldenFormatter.toGolden(actualContent))

        AssertionFailureBuilder.assertionFailure()
            .message("$message. Diff:\n${generateUnifiedDiff(goldenFile, tmpActualFile)}")
            .expected(FileInfo(goldenFile.absolutePathString(), goldenFile.readBytes()))
            .actual(FileInfo(tmpActualFile.absolutePathString(), tmpActualFile.readBytes()))
            .buildAndThrow()
    } else {
        tmpActualFile.deleteIfExists()
    }
}

/**
 * Asserts that the contents of the given [actualFile] are equal to the contents returned by [getExpectedContents],
 * assuming UTF-8 encoding.
 *
 * The [getExpectedContents] function is given the actual contents of the file, and should return the expected contents.
 * This is useful when the assertion needs to check some property of the file contents, like whether it is sorted.
 *
 * If the contents don't match, this assertion fails and writes the expected contents to a `.tmp` file next to the
 * [actualFile]. If [actualFile] doesn't exist at all, this assertion fails without writing anything to disk, because
 * there is probably more to it, and generating a `.tmp` file could hide issues.
 *
 * **Note:** While this may seem almost identical to [assertContentEqualsGoldenFile], this function has the opposite
 * semantics, and has a slightly different behavior.
 */
inline fun assertFileEqualsExpectedContents(
    actualFile: Path,
    getExpectedContents: (actualContents: String) -> String,
    goldenFormatter: GoldenFormatter = IdentityGoldenFormatter,
    message: String = "Comparison failed",
) {
    contract {
        callsInPlace(getExpectedContents, InvocationKind.AT_MOST_ONCE)
    }

    val actualFileContents = goldenFormatter.fromGolden(actualFile.readText())
    if (!actualFile.exists()) {
        fail("Cannot assert on file contents. File does not exist: ${actualFile.absolutePathString()}")
    }

    val tmpExpectedFile = actualFile.resolveSibling(actualFile.name + ".tmp")
    val expectedContents = getExpectedContents(actualFileContents)
    if (actualFileContents != expectedContents) {
        tmpExpectedFile.writeText(goldenFormatter.toGolden(expectedContents))

        AssertionFailureBuilder.assertionFailure()
            .message("$message. Diff:\n${generateUnifiedDiff(actualFile, tmpExpectedFile)}")
            .expected(FileInfo(tmpExpectedFile.absolutePathString(), tmpExpectedFile.readBytes()))
            .actual(FileInfo(actualFile.absolutePathString(), actualFile.readBytes()))
            .buildAndThrow()
    } else {
        tmpExpectedFile.deleteIfExists()
    }
}
