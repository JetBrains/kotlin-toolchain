/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Factory to provide diagnostics on a merged tree.
 *
 * Use this factory to focus on specific scalar values instead of the tree structure.
 *
 * @see TreeDiagnosticFactories
 */
interface TreeDiagnosticFactory {
    fun analyze(root: TreeNode, minimalModule: MinimalModule, problemReporter: ProblemReporter): Unit?
}

/**
 * Get all registered [TreeDiagnosticFactory]s.
 */
val TreeDiagnosticFactories = [
    AndroidTooOldVersionFactory,
    IncorrectSettingsSectionFactory,
    JdkVersionTooLowFactory,
    KotlinCompilerVersionDiagnosticsFactory,
    ObsoleteLibProductTypeDiagnosticsFactory,
    TemplateNameWithoutPostfix,
    UnknownQualifiers,
    UnsupportedLayoutDiagnosticFactory,
    ValidXmlValidation,
]
