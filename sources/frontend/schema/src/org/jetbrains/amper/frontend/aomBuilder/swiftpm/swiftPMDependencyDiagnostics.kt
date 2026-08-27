/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.swiftpm

import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.contexts.TestCtx
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.reading.YamlValue
import org.jetbrains.amper.frontend.tree.reading.asTrace
import org.jetbrains.amper.problems.reporting.ProblemReporter
import kotlin.collections.filterIsInstance

context(_: ProblemReporter)
fun diagnoseSwiftPMDependencyInNonApplePlatform(moduleFragments: List<Fragment>) {
    moduleFragments.filterIsInstance<LeafFragment>().filter {
        !it.platform.isDescendantOf(Platform.APPLE)
    }.filter { !it.isTest }.forEach { fragment ->
        val swiftPMDependencies = fragment.externalDependencies.filterIsInstance<SwiftPMDependencyNotation>()
        swiftPMDependencies.forEach {
            reportBundleError(
                source = it.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.SwiftPMDependencyInNonApplePlatform,
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

context(contexts: Contexts, _: ProblemReporter)
fun diagnoseTestOnlySwiftPMDependency(value: YamlValue) {
    if (contexts.any { it is TestCtx }) {
        reportBundleError(
            source = value.asTrace().asBuildProblemSource(),
            diagnosticId = TreeDiagnosticId.TestOnlySwiftPMDependency,
            messageKey = "dependencies.swiftpm.test.only",
        )
    }
}