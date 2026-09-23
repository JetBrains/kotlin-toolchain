/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isExplicitlySet
import org.jetbrains.amper.frontend.api.isSetInTemplate
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

object KlibsIoNotificationCheckingFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val mavenCentralSettings = module.publishingSettings.mavenCentral
        val notifyKlibsIoDelegate = mavenCentralSettings.notifyKlibsIoDelegate
        if (
            mavenCentralSettings.notifyKlibsIo &&
            notifyKlibsIoDelegate.isExplicitlySet &&
            !notifyKlibsIoDelegate.isSetInTemplate &&
            module.type.supportedPlatforms.size <= 1
        ) {
            problemReporter.reportMessage(
                NotifyKlibsIoRequiresMultiplatform(
                    notifyKlibsIoTrace = notifyKlibsIoDelegate.trace,
                )
            )
        }
    }
}

class NotifyKlibsIoRequiresMultiplatform(
    val notifyKlibsIoTrace: Trace,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.NotifyKlibsIoRequiresMultiplatform
    override val message = SchemaBundle.message("notify.klibs.io.requires.multiplatform")
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = notifyKlibsIoTrace.asBuildProblemSource()
}
