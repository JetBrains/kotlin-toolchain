/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitIntProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.JdkSettings
import org.jetbrains.amper.frontend.schema.MinVersions
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object JdkVersionTooLowFactory : TreeDiagnosticFactory {

    override fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace>() // somehow the computed properties lead to duplicate reports
        root.visitIntProperties<JdkSettings>(JdkSettings::version) { prop, value ->
            val versionTrace = prop.value.trace
            if (value < MinVersions.jdk && reportedPlaces.add(versionTrace)) {
                problemReporter.reportMessage(
                    JdkVersionTooLow(
                        source = PsiBuildProblemSource(
                            versionTrace.extractPsiElementOrNull() ?: return@visitIntProperties
                        ),
                        actualVersion = value,
                        minVersion = MinVersions.jdk,
                    )
                )
            }
        }
    }
}

class JdkVersionTooLow(
    override val source: FileBuildProblemSource,
    val actualVersion: Int,
    val minVersion: Int,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.Generic
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.JdkVersionTooLow
    override val message = SchemaBundle.message("jdk.version.too.low", actualVersion, minVersion)
}
