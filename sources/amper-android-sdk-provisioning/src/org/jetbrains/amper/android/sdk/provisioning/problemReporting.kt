/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MessageBundle
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.div

internal object AndroidSdkProvisioningBundle : MessageBundle("messages.AndroidSdkProvisioningBundle")

enum class AndroidSdkProvisioningDiagnosticId : DiagnosticId {
    UnacceptedLicenses,
}

@OptIn(NonIdealDiagnostic::class) // Android SDK installations are external to the project model
class UnacceptedAndroidSdkLicenses(
    val sdkRoot: Path,
    val packagesByLicenseId: Map<String, List<String>>,
) : BuildProblem {
    override val diagnosticId: DiagnosticId = AndroidSdkProvisioningDiagnosticId.UnacceptedLicenses
    override val source: BuildProblemSource = GlobalBuildProblemSource
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.Generic
    override val message: @Nls String = AndroidSdkProvisioningBundle.message(
        "android.sdk.licenses.unaccepted",
        packagesByLicenseId.entries.joinToString("\n") { entry ->
            AndroidSdkProvisioningBundle.message("android.sdk.missing.license.entry", entry.key, entry.value.joinToString())
        },
        sdkRoot / "cmdline-tools" / "latest" / "bin" / "sdkmanager",
    )
}
