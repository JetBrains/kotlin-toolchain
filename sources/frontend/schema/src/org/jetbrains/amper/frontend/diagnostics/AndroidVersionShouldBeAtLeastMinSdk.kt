/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.SchemaValueDelegate
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.schema.AndroidVersion
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

class AndroidVersionShouldBeAtLeastMinSdk(
    @UsedInIdePlugin
    val versionProp: SchemaValueDelegate<out AndroidVersion?>,
    @UsedInIdePlugin
    val minSdkVersion: AndroidVersion,
    private val versionName: String = versionProp.name,
) : PsiBuildProblem(Level.Error, BuildProblemType.InconsistentConfiguration) {

    override val element: PsiElement
        get() = versionProp.extractPsiElement()

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AndroidVersionShouldBeAtLeastMinSdk

    override val message: @Nls String
        get() = SchemaBundle.message(
            messageKey = "android.version.should.be.at.least.min.sdk",
            versionName,
            versionProp.value,
            minSdkVersion,
        )
}

object AndroidVersionShouldBeAtLeastMinSdkFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Trace?>()

        fun reportIfTooLow(
            minSdkVersion: AndroidVersion,
            versionProp: SchemaValueDelegate<AndroidVersion>,
            versionName: String,
        ) {
            val version = versionProp.value
            if (version >= minSdkVersion) return
            if (!reportedPlaces.add(versionProp.trace)) return

            problemReporter.reportMessage(
                AndroidVersionShouldBeAtLeastMinSdk(
                    versionProp,
                    minSdkVersion = minSdkVersion,
                    versionName = versionName,
                )
            )
        }

        module.fragments.forEach { fragment ->
            val settings = fragment.settings.android
            reportIfTooLow(settings.minSdk, settings.compileSdk.apiLevelDelegate, settings.compileSdkDelegate.name)
            reportIfTooLow(settings.minSdk, settings.targetSdkDelegate, settings.targetSdkDelegate.name)
        }
    }
}
