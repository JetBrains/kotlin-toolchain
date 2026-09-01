/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.swiftpm

import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.TestCtx
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.RecurringTreeVisitorUnit
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.ProblemReporter

context(reporter: ProblemReporter)
fun diagnoseTestOnlySwiftPMDependencies(root: TreeNode) {
    object : RecurringTreeVisitorUnit() {
        override fun visitMap(node: MappingNode) {
            super.visitMap(node)
            if (node.isSwiftDependency() && node.contexts.any { it is TestCtx }) {
                reporter.reportBundleError(
                    source = node.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.TestOnlySwiftPMDependency,
                    messageKey = "dependencies.swiftpm.test.only",
                )
            }
        }

        private fun MappingNode.isSwiftDependency() = when (declaration) {
            DeclarationOfLocalSwiftPMDependencySchema,
            DeclarationOfRemoteSwiftPMDependencySchema,
                -> true
            else -> false
        }
    }.visit(root)
}