/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

object ModuleDependencyDoesntHaveNeededPlatformsFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace?>()
        for (fragment in module.fragments) {
            val fragmentPlatforms = fragment.platforms
            val localDependencies = fragment.externalDependencies.filterIsInstance<LocalModuleDependency>()
            for (localDependency in localDependencies) {
                val localDependencyPlatforms = localDependency.module.leafPlatforms
                val unsupportedPlatforms = findUnsupportedPlatforms(fragmentPlatforms, localDependencyPlatforms)
                if (unsupportedPlatforms.isNotEmpty() && reportedPlaces.add(localDependency.trace)) {
                    problemReporter.reportMessage(
                        ModuleDependencyDoesntHaveNeededPlatforms(localDependency, fragment, unsupportedPlatforms)
                    )
                }
            }
        }
    }
}

private fun findUnsupportedPlatforms(
    dependingPlatforms: Set<Platform>,
    dependencyPlatforms: Set<Platform>,
): Set<Platform> = dependingPlatforms.filterTo(mutableSetOf()) { platform ->
    platform !in dependencyPlatforms && !isAllowedJvmDependencyForAndroid(platform, dependencyPlatforms)
}

private fun isAllowedJvmDependencyForAndroid(
    dependingPlatform: Platform,
    dependencyPlatforms: Set<Platform>,
): Boolean = dependingPlatform == Platform.ANDROID && Platform.JVM in dependencyPlatforms

@Suppress("DEPRECATION")
class ModuleDependencyDoesntHaveNeededPlatforms(
    val dependency: LocalModuleDependency,
    @field:UsedInIdePlugin
    val dependingFragment: Fragment,
    val unsupportedPlatforms: Set<Platform>,
) : PsiBuildProblem(Level.Error, BuildProblemType.InconsistentConfiguration) {

    @Deprecated("Diagnostic consumers should use the source field instead of assuming the problem exposing PSI.")
    override val element: PsiElement
        get() = dependency.extractPsiElement()

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ModuleDependencyDoesntHaveNeededPlatforms

    override val message: @Nls String
        get() = SchemaBundle.message(
            "module.dependency.doesnt.have.needed.platforms",
            dependency.module.userReadableName,
            unsupportedPlatforms.map { "`${it.pretty}`" },
        )
}
