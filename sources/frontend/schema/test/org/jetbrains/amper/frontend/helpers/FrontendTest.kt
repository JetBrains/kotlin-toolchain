/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.helpers

import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.noProblemsReported
import org.jetbrains.amper.test.golden.SystemDependentGoldenFormatter
import org.jetbrains.amper.test.golden.SystemLineSeparatorGoldenFormatter
import org.jetbrains.amper.test.golden.TemplatingGoldenFormatter
import org.jetbrains.amper.test.golden.TemplatingGoldenFormatter.Variable
import org.jetbrains.amper.test.golden.assertContentEqualsGoldenFile
import org.jetbrains.amper.test.golden.plus
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString

/**
 * Represents a single test (usually a single test method) that always has the input file, that is used to read the
 * model, and retrieves expected content only from the corresponding golden file.
 *
 * Assertion is made by comparing the actual content with the expected one and generating
 * special `.tmp` file with the actual content in case of mismatch.
 *
 * Also, the expected content is normalized first and some placeholders are interpolated.
 */
abstract class FrontendTest(
    protected val caseName: String,
    testCase: FrontendTestCaseBase,
) {
    /**
     * Absolute path to the base directory for the golden files.
     */
    protected val goldenFilesDir: Path = testCase.base.absolute()

    protected val inputPath: Path get() = goldenFilesDir.resolve("$caseName.yaml").absolute()

    protected val problemReporter = CollectingProblemReporter()

    protected val pathResolver = TestFrontendPathResolver()

    /**
     * Default test project context.
     */
    protected val projectContext = TestProjectContext(
        projectRoot = AmperFrontendProjectRoot(pathResolver.loadVirtualFile(goldenFilesDir)),
        amperModuleFiles = [pathResolver.loadVirtualFile(inputPath)],
        frontendPathResolver = pathResolver,
    )

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

    /**
     * Check that there are no reported errors in the [problemReporter].
     */
    protected fun assertNoReportedErrors() = assert(problemReporter.noProblemsReported) {
        val concatenatedMessages = problemReporter.problems.joinToString(separator = "\n\t") { it.message }
        "Expected no errors, but got \n\t$concatenatedMessages\n"
    }
}
