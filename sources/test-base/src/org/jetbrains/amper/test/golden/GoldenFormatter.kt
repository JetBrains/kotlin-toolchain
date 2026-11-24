/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

/**
 * A formatter that defines how to convert between the golden file format and the contents comparison format.
 */
interface GoldenFormatter {
    /**
     * Converts the content used for comparison to the format used for comparison.
     */
    fun toGolden(comparisonContent: String): String
    /**
     * Converts the content of the golden file to the format used for comparison.
     */
    fun fromGolden(goldenContent: String): String
}

/**
 * Creates a new [GoldenFormatter] that combines this formatter with the given [other] formatter.
 *
 * This formatter is run first when converting to the golden file format, and last when converting to the comparison
 * format.
 */
operator fun GoldenFormatter.plus(other: GoldenFormatter): GoldenFormatter = GoldenFormatterChain([this, other])

private class GoldenFormatterChain(val formatters: List<GoldenFormatter>) : GoldenFormatter {

    override fun toGolden(comparisonContent: String): String =
        formatters.fold(comparisonContent) { content, formatter -> formatter.toGolden(content) }

    override fun fromGolden(goldenContent: String): String = formatters
        .reversed()
        .fold(goldenContent) { content, formatter -> formatter.fromGolden(content) }
}

/**
 * A no-op [GoldenFormatter], which does not modify the content, and thus ensures the golden file contents and the
 * actual contents are compared as-is.
 */
object IdentityGoldenFormatter : GoldenFormatter {
    override fun toGolden(comparisonContent: String): String = comparisonContent
    override fun fromGolden(goldenContent: String): String = goldenContent
}

@RequiresOptIn(message = "This formatter produces different golden files for different OSes, and normalizes the " +
        "comparison to LF line endings. This means the test code needs to force LF-formatted content for comparison, " +
        "which might hide production issues around line endings. Prefer using the identity, and either using real " +
        "system line separators also for comparison, or forcing LF via gitattributes to avoid runtime normalization.")
annotation class SystemDependentGoldenFormatter

/**
 * A [GoldenFormatter] that ensures the golden file has the system line separators, and comparisons are made with LF.
 *
 * This is useful when the golden file is not a dedicated golden file format, but is a regular source or config file
 * that we assert against (for which we can't enforce stable normalized line endings).
 */
@SystemDependentGoldenFormatter
object SystemLineSeparatorGoldenFormatter : GoldenFormatter {
    override fun toGolden(comparisonContent: String): String = comparisonContent.withLineSeparator(System.lineSeparator())
    override fun fromGolden(goldenContent: String): String = goldenContent.withLineSeparator("\n")

    private fun String.withLineSeparator(sep: String) = lines().joinToString(sep)
}

/**
 * A [GoldenFormatter] that replaces placeholders in the golden file with actual values.
 */
class TemplatingGoldenFormatter(val variables: List<Variable>) : GoldenFormatter {

    data class Variable(
        /**
         * The placeholder value as it should appear in the golden file.
         */
        val placeholder: String,
        /**
         * The real value used for comparisons.
         */
        val value: String,
    )

    override fun toGolden(comparisonContent: String): String = variables
        .fold(comparisonContent) { content, (placeholder, value) ->
            content.replace(value, placeholder)
        }

    override fun fromGolden(goldenContent: String): String = variables
        .reversed()
        .fold(goldenContent) { content, (placeholder, value) ->
            content.replace(placeholder, value)
        }
}
