/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkPackageRequest
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvisioningBundle
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkResult
import org.jetbrains.amper.android.sdk.provisioning.PackagePath
import org.jetbrains.amper.cli.SoftTaskFailureAggregator
import org.jetbrains.amper.cli.SoftTaskFailureException
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path
import kotlin.io.path.div

class GetAndroidPlatformFileFromPackageTask(
    private val packageRequest: AndroidSdkPackageRequest,
    private val androidSdkProvider: AndroidSdkProvider,
    override val taskName: TaskName,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        when (val provisionResult = androidSdkProvider.provision(packageRequest)) {
            // TODO: Add traces to the request? E.g., where the version of Android Platform was defined
            is AndroidSdkResult.Error -> userReadableError(provisionResult.message)
            is AndroidSdkResult.Success -> {
                val androidPackage = provisionResult.androidPackage
                if (!androidPackage.license.checkAccepted(androidSdkProvider.sdkRoot)) {
                    // TODO: Support license acceptance in the interactive mode?
                    throw LicenseCheckException(
                        sdkRoot = androidSdkProvider.sdkRoot,
                        licenseId = androidPackage.license.id,
                        packagePath = androidPackage.packagePath,
                    )
                }

                return Result(listOf(androidPackage.location))
            }
        }
    }

    data class Result(
        val outputs: List<Path>
    ) : TaskResult
}

private class LicenseCheckException(
    val sdkRoot: Path,
    val licenseId: String,
    val packagePath: PackagePath
) : SoftTaskFailureException() {
    override val aggregator: SoftTaskFailureAggregator = LicenseCheckException

    companion object : SoftTaskFailureAggregator {
        override fun aggregate(exceptions: List<SoftTaskFailureException>): UserReadableError {
            val licenseCheckExceptions = exceptions.filterIsInstance<LicenseCheckException>()
            val sdkRoots = licenseCheckExceptions.map { it.sdkRoot }.distinct()
            if (sdkRoots.size != 1) error("License check exceptions have different SDK roots: $sdkRoots. Exceptions: $licenseCheckExceptions")
            val sdkRoot = sdkRoots.single()

            val groupedByLicenseId = licenseCheckExceptions.groupBy { it.licenseId }

            return UserReadableError(
                AndroidSdkProvisioningBundle.message(
                    "android.sdk.licenses.unaccepted",
                    groupedByLicenseId.entries.joinToString("\n") { [licenseId, exceptions] ->
                        AndroidSdkProvisioningBundle.message("android.sdk.missing.license.entry", licenseId, exceptions.map { it.packagePath }.distinct().sortedBy { it.path })
                    },
                    sdkRoot / "cmdline-tools" / "latest" / "bin" / "sdkmanager",
                ),
                exitCode = 1,
            )
        }
    }
}