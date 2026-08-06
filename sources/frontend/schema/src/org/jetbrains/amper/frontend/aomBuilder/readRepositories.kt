/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder

import org.jetbrains.amper.frontend.PublicationRepository
import org.jetbrains.amper.frontend.RepositoryCredentials
import org.jetbrains.amper.frontend.ResolutionRepository
import org.jetbrains.amper.frontend.api.SchemaValueDelegate
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.schema.Module
import org.jetbrains.amper.frontend.schema.Repository
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.mavencentral.MavenCentralDefaultConfiguration
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.properties.readProperties
import kotlin.io.path.exists

/**
 * Reads the [RepositoryCredentials] from the file specified by the schema spec.
 *
 * If something goes wrong, diagnostics are reported via [problemReporter] and `null` is returned.
 */
context(problemReporter: ProblemReporter)
fun Repository.Credentials.readCredentials(): RepositoryCredentials? {
    if (!file.exists()) {
        problemReporter.reportBundleError(
            source = fileDelegate.asBuildProblemSource(),
            diagnosticId = FrontendDiagnosticId.CredentialsFileDoesNotExist,
            messageKey = "credentials.file.does.not.exist",
            file.normalize(),
            problemType = BuildProblemType.UnresolvedReference,
        )
        return null
    }

    val credentialProperties = file.readProperties()

    fun getCredProperty(keyProperty: SchemaValueDelegate<String>): String? {
        val property = credentialProperties.getProperty(keyProperty.value)
        if (property == null) {
            problemReporter.reportBundleError(
                source = keyProperty.asBuildProblemSource(),
                diagnosticId = FrontendDiagnosticId.CredentialsFileDoesNotHaveKey,
                messageKey = "credentials.file.does.not.have.key",
                file.normalize(),
                keyProperty.value,
                credentialProperties.keys.map { "`$it`" },
                problemType = BuildProblemType.UnresolvedReference,
            )
        }
        return property
    }

    val userName = getCredProperty(usernameKeyDelegate)
    val password = getCredProperty(passwordKeyDelegate)
    return RepositoryCredentials(
        userName = userName ?: return null,
        password = password ?: return null,
    )
}

context(_: ProblemReporter)
internal fun Module.readResolveRepositories(): List<ResolutionRepository> {
    val customRepositories = repositories.orEmpty().filter { it.resolve }.map { repository ->
        ResolutionRepository(
            id = repository.id,
            url = repository.url,
            credentials = repository.credentials?.readCredentials(),
        )
    }
    return (defaultMavenRepositories + customRepositories)
        // deduplicating repository list by repository ID, taking the last entry corresponding to the id only.
        .asReversed()
        .distinctBy { it.id }
        .asReversed()
}

internal fun Module.readPublishRepositories(): List<PublicationRepository> =
    repositories.orEmpty().filter { it.publish }.map { repository ->
        PublicationRepository(
            id = repository.id,
            url = repository.url,
            credentialsSource = repository.credentials,
        )
    }.distinctBy { it.id }

private val defaultMavenRepositories = [
    ResolutionRepository(
        id = "mavenCentral",
        url = MavenCentralDefaultConfiguration.url,
    ),
    ResolutionRepository(
        id = "mavenGoogle",
        url = "https://maven.google.com",
    ),
]
