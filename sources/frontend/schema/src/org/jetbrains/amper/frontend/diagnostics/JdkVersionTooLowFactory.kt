/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitIntProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.JdkSettings
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object JdkVersionTooLowFactory : TreeDiagnosticFactory {

    private const val MinimumSupportedJdkVersion = 17

    override fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace>() // somehow the computed properties lead to duplicate reports
        root.visitIntProperties<JdkSettings>(JdkSettings::version) { prop, value ->
            val versionTrace = prop.value.trace
            if (value < MinimumSupportedJdkVersion && reportedPlaces.add(versionTrace)) {
                problemReporter.reportMessage(
                    JdkVersionTooLow(
                        element = versionTrace.extractPsiElementOrNull() ?: return@visitIntProperties,
                        actualVersion = value,
                        minVersion = MinimumSupportedJdkVersion,
                    )
                )
            }
        }
    }
}

class JdkVersionTooLow(
    override val element: PsiElement,
    val actualVersion: Int,
    val minVersion: Int,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.JdkVersionTooLow
    override val message = SchemaBundle.message("jdk.version.too.low", actualVersion, minVersion)
}
