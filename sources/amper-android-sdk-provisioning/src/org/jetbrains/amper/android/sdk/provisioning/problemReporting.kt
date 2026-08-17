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

object AndroidSdkProvisioningBundle : MessageBundle("messages.AndroidSdkProvisioningBundle")

enum class AndroidSdkProvisioningDiagnostic : DiagnosticId {
    LocalPackageWithoutManifest,
}

@OptIn(NonIdealDiagnostic::class)
data class LocalPackageWithoutManifest(
    val packageName: PackagePath,
    val packagePath: Path,
) : BuildProblem {
    override val diagnosticId: DiagnosticId = AndroidSdkProvisioningDiagnostic.LocalPackageWithoutManifest
    override val source: BuildProblemSource = GlobalBuildProblemSource
    override val message: @Nls String
        get() = AndroidSdkProvisioningBundle.message("local.package.without.manifest", packageName, packagePath)
    override val level: Level = Level.Warning
    override val type: BuildProblemType = BuildProblemType.Generic
}