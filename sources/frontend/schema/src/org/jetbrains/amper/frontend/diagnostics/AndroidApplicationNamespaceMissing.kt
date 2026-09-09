/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object AndroidApplicationNamespaceMissingFactory : AomSingleModuleDiagnosticFactory {
    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        if (module.type != ProductType.ANDROID_APP) return

        val androidSettings = module.fragments.first { !it.isTest }.settings.android
        if (androidSettings.namespace == null) {
            problemReporter.reportMessage(AndroidApplicationNamespaceMissing(module))
        }
    }
}

class AndroidApplicationNamespaceMissing(
    module: AmperModule,
) : BuildProblem {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AndroidApplicationNamespaceMissing
    override val message: String = SchemaBundle.message("android.application.namespace.missing")
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = module.commonModuleNode.product.typeDelegate.trace.asBuildProblemSource()
}