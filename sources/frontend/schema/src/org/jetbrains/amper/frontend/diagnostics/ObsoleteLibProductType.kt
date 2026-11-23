/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.ModuleProduct
import org.jetbrains.amper.frontend.tree.EnumNode
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object ObsoleteLibProductTypeDiagnosticsFactory : TreeDiagnosticFactory {

    override fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter) {
        root.visitProperties<ModuleProduct, EnumNode>(ModuleProduct::type) { _, node ->
            if (node.entryName == "lib") {
                problemReporter.reportMessage(
                    ObsoleteLibProductType(
                        source = PsiBuildProblemSource(node.trace.extractPsiElementOrNull() ?: return@visitProperties),
                    )
                )
            }
        }
    }
}

class ObsoleteLibProductType(
    override val source: FileBuildProblemSource,
) : BuildProblem {
    override val level: Level = Level.Warning
    override val type: BuildProblemType = BuildProblemType.ObsoleteDeclaration
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ObsoleteLibProductType
    override val message = SchemaBundle.message("obsolete.product.type.lib")
}
