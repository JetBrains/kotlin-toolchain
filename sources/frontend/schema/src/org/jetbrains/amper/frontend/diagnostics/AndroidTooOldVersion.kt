/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.AndroidCompileSdkVersion
import org.jetbrains.amper.frontend.schema.AndroidSettings
import org.jetbrains.amper.frontend.schema.AndroidVersion
import org.jetbrains.amper.frontend.schema.MinVersions
import org.jetbrains.amper.frontend.tree.IntNode
import org.jetbrains.amper.frontend.tree.KeyValue
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

class AndroidTooOldVersion(
    override val element: PsiElement,
    used: AndroidVersion,
    minVersion: AndroidVersion,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AndroidVersionTooOld
    override val message = SchemaBundle.message("too.old.android.version", used, minVersion)
}

object AndroidTooOldVersionFactory : TreeDiagnosticFactory {

    override fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<PsiElement>() // somehow the computed properties lead to duplicate reports

        fun reportTooOldVersion(keyValue: KeyValue, node: IntNode) {
            val version = AndroidVersion(node.value)
            val versionTraceElement = keyValue.value.trace.extractPsiElementOrNull() ?: return
            if (version < MinVersions.android && reportedPlaces.add(versionTraceElement)) {
                problemReporter.reportMessage(
                    AndroidTooOldVersion(
                        element = versionTraceElement,
                        used = version,
                        minVersion = MinVersions.android,
                    )
                )
            }
        }

        root.visitProperties<AndroidCompileSdkVersion, IntNode>(
            AndroidCompileSdkVersion::apiLevel,
            visitSelected = ::reportTooOldVersion,
        )
        root.visitProperties<AndroidSettings, IntNode>(
            AndroidSettings::minSdk,
            AndroidSettings::targetSdk,
            visitSelected = ::reportTooOldVersion,
        )
    }
}
