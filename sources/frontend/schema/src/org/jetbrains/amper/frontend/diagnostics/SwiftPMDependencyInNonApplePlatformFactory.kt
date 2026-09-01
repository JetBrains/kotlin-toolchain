/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.problems.reporting.ProblemReporter

object SwiftPMDependencyInNonApplePlatformFactory : AomSingleModuleDiagnosticFactory {
    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        module.leafFragments.filter {
            !it.platform.isDescendantOf(Platform.APPLE)
        }.filter { !it.isTest }.forEach { fragment ->
            fragment.externalDependencies.filterIsInstance<SwiftPMDependencyNotation>().forEach {
                problemReporter.reportBundleError(
                    source = it.trace.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.SwiftPMDependencyInNonApplePlatform,
                    messageKey = "dependencies.swiftpm.in.non.apple.platform",
                    when (it) {
                        is LocalSwiftPMDependencyNotation -> it.swiftPMDependency.absolutePath.toString()
                        is RemoteSwiftPMDependencyNotation -> it.swiftPMDependency.repository.value
                    },
                    fragment.platform.name,
                )
            }
        }
    }
}