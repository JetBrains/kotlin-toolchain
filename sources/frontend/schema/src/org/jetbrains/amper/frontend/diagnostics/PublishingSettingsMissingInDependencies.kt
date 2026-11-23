/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

object PublishingSettingsMissingInDependencies : AomModelDiagnosticFactory {

    override fun analyze(model: Model, problemReporter: ProblemReporter) {
        model.modules.forEach { module ->
            if (module.isPublishingEnabled()) {
                module.fragments
                    .filterNot { it.isTest }
                    .flatMap { it.externalDependencies }
                    .filterIsInstance<LocalModuleDependency>()
                    .filterNot { it.module.isPublishingEnabled() }
                    .forEach { dep ->
                        problemReporter.reportMessage(PublishingSettingsMissingInDependency(module, dep))
                    }
            }
        }
    }
}

class PublishingSettingsMissingInDependency(
    val module: AmperModule,
    @field:UsedInIdePlugin
    val dependency: LocalModuleDependency,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: FileBuildProblemSource by lazy { PsiBuildProblemSource(dependency.extractPsiElement()) }

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.PublishingSettingsMissingInDependencies
    override val message: @Nls String
        get() = SchemaBundle.message(
            "published.module.0.depends.on.non.published.module.1",
            module.userReadableName,
            dependency.module.userReadableName,
        )
}