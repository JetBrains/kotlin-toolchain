/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.SchemaValueDelegate
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isExplicitlySet
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

object AndroidSettingsCannotBeNullFactory : AomSingleModuleDiagnosticFactory {
    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace>()

        fun reportIfExplicitlyNull(setting: SchemaValueDelegate<*>) {
            if (setting.value == null &&
                setting.isExplicitlySet &&
                reportedPlaces.add(setting.trace)
            ) {
                problemReporter.reportMessage(AndroidSettingCannotBeNull(setting))
            }
        }

        module.fragments.filter { Platform.ANDROID in it.platforms }.forEach { fragment ->
            val androidSettings = fragment.settings.android
            reportIfExplicitlyNull(androidSettings.namespaceDelegate)
            reportIfExplicitlyNull(androidSettings.applicationIdDelegate)
        }
    }
}

class AndroidSettingCannotBeNull(
    val setting: SchemaValueDelegate<*>,
) : PsiBuildProblem(Level.Error, BuildProblemType.InconsistentConfiguration) {
    override val element: PsiElement
        get() = setting.extractPsiElement()

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AndroidSettingCannotBeNull

    override val message: @Nls String
        get() = SchemaBundle.message("android.setting.cannot.be.null", setting.name)
}