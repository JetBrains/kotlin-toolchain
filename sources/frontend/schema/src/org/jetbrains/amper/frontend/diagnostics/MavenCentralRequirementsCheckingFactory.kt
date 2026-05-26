/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.SchemaValueDelegate
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isSetInTemplate
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter

object MavenCentralRequirementsCheckingFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        if (!module.publishingSettings.mavenCentral.enabled) {
            return
        }
        val mavenCentralEnabledProp = module.publishingSettings.mavenCentral.enabledDelegate

        // We don't report this if maven central publication is set in a template, because it is quite common to
        // configure this generally in a common template, and apply it to all modules (not only the published ones)
        if (!module.publishingSettings.enabled && !mavenCentralEnabledProp.isSetInTemplate) {
            problemReporter.reportMessage(MavenCentralPublishingEnabledButPublishingDisabled(
                mavenCentralEnabledTrace = mavenCentralEnabledProp.trace,
                publishingDisabledTrace = module.publishingSettings.enabledDelegate.trace,
            ))
        }

        if (!module.publishingSettings.signArtifacts) {
            problemReporter.reportMessage(
                SigningIsRequiredForMavenCentralPublication(
                    mavenCentralEnabledTrace = mavenCentralEnabledProp.trace,
                    signingDisabledTrace = module.publishingSettings.signArtifactsDelegate.trace,
                )
            )
        }

        if (!module.publishingSettings.publishSources) {
            problemReporter.reportMessage(
                SourcesJarIsRequiredForMavenCentralPublication(
                    mavenCentralEnabledTrace = mavenCentralEnabledProp.trace,
                    publishSourcesDisabledTrace = module.publishingSettings.publishSourcesDelegate.trace,
                )
            )
        }
        val missingProperties = mutableSetOf<MissingProperty>()
        if (module.description == null) {
            missingProperties.add(MissingProperty("description", module.commonModuleNode.descriptionDelegate))
        }
        if (module.publishingSettings.group == null) {
            missingProperties.add(MissingProperty("publishing.group", module.publishingSettings.groupDelegate))
        }
        if (module.publishingSettings.version == null) {
            missingProperties.add(MissingProperty("publishing.version", module.publishingSettings.versionDelegate))
        }
        if (module.publishingSettings.pom.url == null) {
            missingProperties.add(MissingProperty("publishing.pom.url", module.publishingSettings.pom.urlDelegate))
        }
        if (module.publishingSettings.pom.licenses.isEmpty()) {
            missingProperties.add(MissingProperty("publishing.pom.licenses", module.publishingSettings.pom.licensesDelegate))
        }
        if (module.publishingSettings.pom.developers.isEmpty()) {
            missingProperties.add(MissingProperty("publishing.pom.developers", module.publishingSettings.pom.developersDelegate))
        }
        if (module.publishingSettings.pom.scm.url == null) {
            missingProperties.add(MissingProperty("publishing.pom.scm.url", module.publishingSettings.pom.scm.urlDelegate))
        }
        // We only report the missing pom.scm.connection if pom.scm.url is set, because if both are missing,
        // the problem will generally be solved by just setting the pom.scm.url.
        if (module.publishingSettings.pom.scm.url != null && module.publishingSettings.pom.scm.connection == null) {
            missingProperties.add(MissingProperty("publishing.pom.scm.connection", module.publishingSettings.pom.scm.connectionDelegate))
        }
        // We only report the missing pom.scm.developerConnection if pom.scm.url is set, because if both are missing,
        // the problem will generally be solved by just setting the pom.scm.url.
        if (module.publishingSettings.pom.scm.url != null && module.publishingSettings.pom.scm.developerConnection == null) {
            missingProperties.add(MissingProperty("publishing.pom.scm.developerConnection", module.publishingSettings.pom.scm.developerConnectionDelegate))
        }
        if (missingProperties.isNotEmpty()) {
            problemReporter.reportMessage(
                RequiredPropertiesMissingForMavenCentralPublication(
                    mavenCentralEnabledTrace = mavenCentralEnabledProp.trace,
                    missingProperties = missingProperties,
                )
            )
        }
    }
}

class MissingProperty(
    val path: String,
    val property: SchemaValueDelegate<*>,
)

class MavenCentralPublishingEnabledButPublishingDisabled(
    val mavenCentralEnabledTrace: Trace,
    val publishingDisabledTrace: Trace,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.MavenCentralPublishingEnabledButPublishingDisabled
    override val message = SchemaBundle.message("maven.central.requires.publishing")
    override val level: Level = Level.Warning
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = singleOrMultiBuildProblemSource(
        mavenCentralEnabledTrace,
        publishingDisabledTrace,
        groupingMessage = SchemaBundle.message("maven.central.requires.publishing.grouping"),
    )
}

class SigningIsRequiredForMavenCentralPublication(
    val mavenCentralEnabledTrace: Trace,
    val signingDisabledTrace: Trace,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.SigningIsRequiredForMavenCentralPublication
    override val message = SchemaBundle.message("maven.central.requires.signing")
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = singleOrMultiBuildProblemSource(
        mavenCentralEnabledTrace,
        signingDisabledTrace,
        groupingMessage = SchemaBundle.message("maven.central.requires.signing.grouping"),
    )
}

class SourcesJarIsRequiredForMavenCentralPublication(
    val mavenCentralEnabledTrace: Trace,
    val publishSourcesDisabledTrace: Trace,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.SourcesJarIsRequiredForMavenCentralPublication
    override val message = SchemaBundle.message("maven.central.requires.publishSources")
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = singleOrMultiBuildProblemSource(
        mavenCentralEnabledTrace,
        publishSourcesDisabledTrace,
        groupingMessage = SchemaBundle.message("maven.central.requires.publishSources.grouping"),
    )
}

class RequiredPropertiesMissingForMavenCentralPublication(
    val mavenCentralEnabledTrace: Trace,
    val missingProperties: Set<MissingProperty>,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.RequiredPropertiesMissingForMavenCentralPublication
    override val message = SchemaBundle.message(
        "maven.central.required.properties.missing",
        missingProperties.sortedBy { it.path }.joinToString("\n") { " - `${it.path}`" }
    )
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = mavenCentralEnabledTrace.asBuildProblemSource()
}

private fun singleOrMultiBuildProblemSource(
    mainTrace: Trace,
    vararg otherTraces: Trace,
    groupingMessage: String,
): BuildProblemSource {
    val otherSources = otherTraces.map { it.asBuildProblemSource() }.filterIsInstance<FileBuildProblemSource>()
    if (otherSources.isEmpty()) {
        return mainTrace.asBuildProblemSource()
    }
    val allSources = listOf(mainTrace.asBuildProblemSource()) + otherSources
    return MultipleLocationsBuildProblemSource(
        sources = allSources.filterIsInstance<FileBuildProblemSource>(),
        groupingMessage = groupingMessage,
    )
}
