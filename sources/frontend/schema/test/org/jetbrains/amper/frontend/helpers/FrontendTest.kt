/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.helpers

import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.noProblemsReported
import org.jetbrains.amper.test.golden.GoldFileTest
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.readText

/**
 * [GoldFileTest] that always has the input file, that is used to read the model.
 * 
 * See [GoldFileTest] for the assertion logic.
 */
abstract class FrontendTest(
    caseName: String,
    testCase: FrontendTestCaseBase,
) : GoldFileTest(caseName, testCase.base) {
    protected val inputPath: Path get() = base.resolve("$caseName.yaml").absolute()

    protected val problemReporter = CollectingProblemReporter()

    protected val pathResolver = TestFrontendPathResolver()

    protected val inputFile by lazy { pathResolver.loadVirtualFile(inputPath) }

    /**
     * Default test project context.
     */
    protected val projectContext = TestProjectContext(
        projectRoot = AmperFrontendProjectRoot(pathResolver.loadVirtualFile(base)),
        amperModuleFiles = listOf(inputFile),
        frontendPathResolver = pathResolver,
    )
    
    override fun getInputContent() = inputPath.readText()

    /**
     * Check that there are no reported errors in the [problemReporter].
     */
    protected fun assertNoReportedErrors() = assert(problemReporter.noProblemsReported) {
        val concatenatedMessages = problemReporter.problems.joinToString(separator = "\n\t") { it.message }
        "Expected no errors, but got \n\t$concatenatedMessages\n"
    }
}