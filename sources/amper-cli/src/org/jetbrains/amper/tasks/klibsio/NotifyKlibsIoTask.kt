/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.klibsio

import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import org.jetbrains.amper.cli.terminal.printCompletedMilestone
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.MAVEN_CENTRAL_REPOSITORY_ID
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.klibsio.KlibsIoNotifier
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("klibs.io notifier")

internal class NotifyKlibsIoTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    private val terminal: Terminal,
) : PublishTask {

    override val targetRepositoryId = MAVEN_CENTRAL_REPOSITORY_ID
    override val publishesToLocalRepository = false

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        module.notifyKlibsIo(failOnError = false, terminal = terminal)
        return EmptyTaskResult
    }
}

/**
 * Sends a notification to klibs.io with KMP library coordinates for it to be later indexed on the website.
 *
 * @param failOnError when set to true, a failure to notify klibs.io fails the build; warns without failing otherwise.
 */
internal suspend fun AmperModule.notifyKlibsIo(failOnError: Boolean, terminal: Terminal) {
    if (!isMultiplatformPublication()) {
        reportNotificationFailure(
            failOnError = failOnError,
            message = "klibs.io notification has not been sent: only Kotlin Multiplatform publications are supported by the notifier.",
        )
        return
    }

    val coordinates = publicationCoordinates(Platform.COMMON)
    val apiUrl = publishingSettings.mavenCentral.notifyKlibsIo.apiUrl
    val notifier = KlibsIoNotifier(httpClient = amperHttpClient, apiUrl = apiUrl)

    logger.info("Notifying klibs.io: POST $apiUrl.")
    try {
        val responseBody = notifier.notifyArtifactPublished(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            version = coordinates.version ?: error("Missing version of the publication coordinates."),
        )
        terminal.printCompletedMilestone("Successfully notified klibs.io: $responseBody.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        reportNotificationFailure(
            failOnError = failOnError,
            message = "Failed to notify klibs.io: HTTP ${e.response.status.value}\n${e.response.bodyAsText()}.",
            cause = e,
        )
    } catch (e: Exception) {
        reportNotificationFailure(
            failOnError = failOnError,
            message = "Failed to reach klibs.io at $apiUrl: $e.",
            cause = e,
        )
    }
}

internal fun reportNotificationFailure(failOnError: Boolean, message: String, cause: Exception? = null) {
    if (failOnError) {
        userReadableError(message, cause)
    }
    logger.warn(message)
}
