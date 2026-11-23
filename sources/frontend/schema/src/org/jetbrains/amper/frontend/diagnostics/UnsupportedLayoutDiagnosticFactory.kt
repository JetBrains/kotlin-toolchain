/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.helpers.visitEnumProperties
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.AmperLayout
import org.jetbrains.amper.frontend.schema.Module
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

class UnsupportedLayoutBuildProblem(
    override val source: FileBuildProblemSource,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.UnsupportedLayout
    override val message: @Nls String = SchemaBundle.message("module.layout.unsupported")
}

object UnsupportedLayoutDiagnosticFactory: TreeDiagnosticFactory {

    override fun analyze(
        root: TreeNode,
        minimalModule: MinimalModule,
        problemReporter: ProblemReporter,
    ) {
        root.visitEnumProperties<Module, AmperLayout>(Module::layout) { prop, layout ->
            if (layout == AmperLayout.MAVEN_LIKE && minimalModule.product.type !in setOf(ProductType.JVM_APP, ProductType.JVM_LIB)) {
                val element = prop.value.trace.extractPsiElementOrNull() ?: return@visitEnumProperties
                problemReporter.reportMessage(UnsupportedLayoutBuildProblem(PsiBuildProblemSource(element)))
            }
        }
    }
}
