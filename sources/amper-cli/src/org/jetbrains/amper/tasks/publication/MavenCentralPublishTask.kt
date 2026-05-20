/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.publication

import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.PublishingMode
import org.jetbrains.amper.mavencentral.DeploymentState
import org.jetbrains.amper.mavencentral.PublisherPortalClient
import org.jetbrains.amper.mavencentral.PublishingType
import org.jetbrains.amper.mavencentral.UserToken
import org.jetbrains.amper.mavencentral.pollStatus
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private const val MAVEN_CENTRAL_REPOSITORY_ID = "mavenCentral"

class MavenCentralPublishTask(
    override val taskName: TaskName,
    override val module: AmperModule,
) : PublishTask {
    override val targetRepositoryId = MAVEN_CENTRAL_REPOSITORY_ID

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        val zipBundle = dependenciesResult.filterIsInstance<PrepareMavenCentralBundleTask.Result>()
            .singleOrNull()
            ?.mavenCentralZipBundle
            ?: error("Expected single MavenCentralPackageTask.Result from task dependencies")

        val publisherPortalClient = PublisherPortalClient(
            httpClient = amperHttpClient,
            userToken = getMavenCentralToken(),
        )

        logger.info("Uploading Maven Central bundle for module `${module.userReadableName}`…")
        val publishingMode = module.publishingSettings.mavenCentral.publishingMode
        val deploymentId = spanBuilder("Upload Maven Central bundle")
            .setAmperModule(module)
            .use {
                publisherPortalClient.uploadBundle(
                    zipBundle = zipBundle,
                    publishingType = when (publishingMode) {
                        PublishingMode.Manual -> PublishingType.UserManaged
                        PublishingMode.Auto -> PublishingType.Automatic
                    },
                )
            }
        logger.info("Maven Central bundle uploaded successfully for module `${module.userReadableName}` as deployment `${deploymentId}`")

        // TODO change from logging to animated progress + persistent messages for completed steps
        publisherPortalClient
            .pollStatus(
                deploymentId = deploymentId,
                // TODO maybe use an exponential delay with cap, or fill in intermediate states
                period = 300.milliseconds,
                stopAfterValidated = when (publishingMode) {
                    PublishingMode.Manual -> true // we don't want the build to wait for user action
                    PublishingMode.Auto -> false
                }
            )
            .collect {
                val statusMessage = when (it.deploymentState) {
                    DeploymentState.PENDING -> "Waiting for validation service to pick up deployment `${deploymentId}`…"
                    DeploymentState.VALIDATING -> "Validating deployment `${deploymentId}`…"
                    DeploymentState.VALIDATED -> "Deployment `${deploymentId}` passed validation!"
                    DeploymentState.PUBLISHING -> "Publishing deployment `${deploymentId}` to Maven Central…"
                    DeploymentState.PUBLISHED -> "Deployment $deploymentId published!"
                    // TODO get additional information from the status object? There is supposedly an 'errors' field in this case.
                    DeploymentState.FAILED -> userReadableError{
                        appendLine("Maven Central publication failed for module ${module.userReadableName} (deployment ID: '$deploymentId'):")
                        it.errors.forEach { (group, messages) ->
                            appendLine("  $group:")
                            messages.forEach {
                                appendLine("   - $it")
                            }
                        }
                        appendLine()
                        appendLine("Visit https://central.sonatype.com/publishing/deployments for more info about your deployment.")
                    }
                }
                logger.info(statusMessage)
            }

        if (publishingMode == PublishingMode.Manual) {
            logger.info("The Maven Central bundle was successfully uploaded and validated for module " +
                    "'${module.userReadableName}' with deployment ID '$deploymentId'.\n" +
                    "You may now check manually if the deployment is correct and decide whether to drop or publish " +
                    "it to Maven Central.\n" +
                    "Visit https://central.sonatype.com/publishing/deployments to find your deployment.\n\n" +
                    "If you wish to automatically publish after a successful validation in the future, set the " +
                    "'settings.publishing.mavenCentral.publishingMode' to 'auto'.")
        }

        return EmptyTaskResult
    }
}

private fun getMavenCentralToken(): UserToken {
    val username = System.getenv("KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME")
    val password = System.getenv("KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD")
    if (username == null || password == null) {
        userReadableError(
            "The KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME and KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD environment " +
                    "variables are required to publish to Maven Central.\n\n" +
                    "If you don't have credentials yet (a.k.a \"Portal Token\"), please create them by following " +
                    "the instructions at " +
                    "https://central.sonatype.org/publish/generate-portal-token/"
        )
    }
    return UserToken.from(username, password)
}

