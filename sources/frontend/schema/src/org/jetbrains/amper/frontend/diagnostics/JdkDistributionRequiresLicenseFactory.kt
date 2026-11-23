/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.schema.JvmDistribution
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object JdkDistributionRequiresLicenseFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val jdkSettings = module.jdkSettings
        val acceptableDistributions = jdkSettings.distributions ?: return
        val acknowledgedLicenses = jdkSettings.acknowledgedLicenses.toSet()
        val missingLicenses = acceptableDistributions.filter { it.value.requiresLicense && it.value !in acknowledgedLicenses }
        missingLicenses.forEach { missingLicense ->
            problemReporter.reportMessage(JdkDistributionRequiresLicense(missingLicense))
        }
    }
}

class JdkDistributionRequiresLicense(
    val distribution: TraceableValue<JvmDistribution>,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: FileBuildProblemSource by lazy {
        PsiBuildProblemSource(distribution.trace.extractPsiElement())
    }

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.JdkDistributionRequiresLicense
    override val message = SchemaBundle.message("jdk.distribution.requires.license", distribution.value.schemaValue)
}
