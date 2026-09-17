/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.MAVEN_CENTRAL_REPOSITORY_ID
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.isMavenLocal
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Reports local Swift package dependencies of modules that are configured to be published to the remote repository.
 *
 * Local Swift packages are published as absolute paths (this is also what KGP does), so the consumers of the library
 * can only resolve them on the machine that published it. This is acceptable when publishing to the local Maven
 * repository, but not to repositories that are shared with other machines.
 *
 * This is only a warning, because the module can still be published to the local Maven repository, and can still be
 * built, tested, and run. The actual publication to a shared repository fails with an error.
 */
object LocalSwiftPackageInRemotePublicationFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        if (!module.isPublishingEnabled()) return

        val remoteRepositoryIds = module.remotePublicationRepositoryIds()
        if (remoteRepositoryIds.isEmpty()) return

        module.declaredLocalSwiftPackages().forEach { notation ->
            problemReporter.reportBundleError(
                source = notation.asBuildProblemSource(),
                diagnosticId = FrontendDiagnosticId.LocalSwiftPackageInRemotePublication,
                messageKey = "dependencies.swiftpm.local.in.remote.publication",
                notation.swiftPMDependency.packageName,
                remoteRepositoryIds.userReadableRepositoryList(),
                level = Level.Warning,
                problemType = BuildProblemType.InconsistentConfiguration,
            )
        }
    }
}

/**
 * The IDs of the remote repositories this module is published to.
 *
 * The local Maven repository is excluded, because it is only visible on the machine that published to it, so absolute
 * paths can still be resolved there.
 *
 * Maven Central publication is enabled via `settings.publishing.mavenCentral` rather than by a repository declaration,
 * so it is added separately (since it is still "possible to declare a "publishable" repository with ID `mavenCentral`).
 */
private fun AmperModule.remotePublicationRepositoryIds(): List<String> = buildList {
    if (publishingSettings.mavenCentral.enabled) {
        add(MAVEN_CENTRAL_REPOSITORY_ID)
    }
    mavenPublishRepositories.filterNot { it.isMavenLocal }.mapTo(this) { it.id }
}.distinct()

private fun List<String>.userReadableRepositoryList(): String {
    val quotedIds = joinToString(", ") { "`$it`" }
    return if (size > 1) "the repositories $quotedIds" else "the repository $quotedIds"
}
