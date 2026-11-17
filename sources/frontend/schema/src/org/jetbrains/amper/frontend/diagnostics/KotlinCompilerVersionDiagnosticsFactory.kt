/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitWrappedStringProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.KotlinCompilerVersionPattern
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.frontend.schema.MinVersions
import org.jetbrains.amper.frontend.schema.kotlin.KotlinSettings
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object KotlinCompilerVersionDiagnosticsFactory : TreeDiagnosticFactory {

    override fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace>() // somehow the computed properties lead to duplicate reports
        root.visitWrappedStringProperties(KotlinSettings::version) { prop, value ->
            val versionTrace = prop.value.trace
            if (!KotlinCompilerVersionPattern.matches(value) && reportedPlaces.add(versionTrace)) {
                problemReporter.reportMessage(
                    InvalidKotlinCompilerVersion(
                        element = versionTrace.extractPsiElementOrNull() ?: return@visitWrappedStringProperties,
                        actualVersion = value,
                    )
                )
            } else if (KotlinVersion(value) < MinVersions.kotlin && reportedPlaces.add(versionTrace)) {
                problemReporter.reportMessage(
                    KotlinCompilerVersionTooLow(
                        element = versionTrace.extractPsiElementOrNull() ?: return@visitWrappedStringProperties,
                        actualVersion = value,
                        minVersion = MinVersions.kotlin.notation,
                    )
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
class InvalidKotlinCompilerVersion(
    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement,
    val actualVersion: String,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.InvalidKotlinCompilerVersion
    override val message = SchemaBundle.message("invalid.kotlin.compiler.version", actualVersion)
}

@Suppress("DEPRECATION")
class KotlinCompilerVersionTooLow(
    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement,
    val actualVersion: String,
    val minVersion: String,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.KotlinCompilerVersionTooLow
    override val message = SchemaBundle.message("kotlin.compiler.version.too.low", actualVersion, minVersion)
}
