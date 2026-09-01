/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.ancestralPath
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

object ConflictingMavenDependencyDeclarationsFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        if (!module.isPublishingEnabled()) return

        val reportedPlaces = mutableSetOf<Trace>()
        for (leafFragment in module.leafFragments.filterNot { it.isTest }) {
            val declarationsByManagementKey = mutableMapOf<MavenManagementKey, MavenDependencyDeclaration>()
            for (fragment in leafFragment.ancestralPath()) {
                for (dependency in fragment.externalDependencies.filterIsInstance<MavenDependencyBase>()) {
                    val declaration = MavenDependencyDeclaration(dependency)
                    val existingDeclaration = declarationsByManagementKey.putIfAbsent(
                        dependency.managementKey,
                        declaration,
                    ) ?: continue

                    if (
                        declaration.publicationSemantics != existingDeclaration.publicationSemantics &&
                        reportedPlaces.add(declaration.dependency.trace)
                    ) {
                        problemReporter.reportMessage(
                            ConflictingMavenDependencyDeclarations(declaration, existingDeclaration, leafFragment)
                        )
                    }
                }
            }
        }
    }
}

private data class MavenDependencyDeclaration(
    val dependency: MavenDependencyBase,
) {
    val publicationSemantics = MavenPublicationSemantics(
        version = dependency.coordinates.version?.value,
        scope = dependency.publicationScope,
    )
}

private data class MavenManagementKey(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val classifier: String?,
    val isBom: Boolean,
) {
    override fun toString(): String = "$groupId:$artifactId:$type" + (classifier?.let { ":$it" } ?: "")
}

private val MavenDependencyBase.managementKey: MavenManagementKey
    get() = MavenManagementKey(
        groupId = coordinates.groupId,
        artifactId = coordinates.artifactId,
        type = when (this) {
            is MavenDependency -> coordinates.packagingType ?: "jar"
            is BomDependency -> "pom"
        },
        classifier = coordinates.classifier,
        // BOMs and regular dependencies are written to separate POM sections and are deduplicated separately.
        isBom = this is BomDependency,
    )

private data class MavenPublicationSemantics(
    val version: String?,
    val scope: MavenPublicationScope,
)

private enum class MavenPublicationScope(val displayName: String) {
    Compile("compile"),
    Runtime("runtime"),
    Provided("provided"),
    Import("import"),
    None("none"),
}

// Keep this mapping aligned with DefaultScopedNotation.mavenScopeName() in amper-maven-publish/pom.kt.
private val MavenDependencyBase.publicationScope: MavenPublicationScope
    get() = when (this) {
        is BomDependency -> MavenPublicationScope.Import
        is MavenDependency -> when {
            compile && runtime && exported -> MavenPublicationScope.Compile
            compile && runtime -> MavenPublicationScope.Runtime
            compile && exported -> MavenPublicationScope.Compile
            compile -> MavenPublicationScope.Provided
            runtime -> MavenPublicationScope.Runtime
            else -> MavenPublicationScope.None
        }
    }

private class ConflictingMavenDependencyDeclarations(
    private val declaration: MavenDependencyDeclaration,
    private val conflictingDeclaration: MavenDependencyDeclaration,
    private val leafFragment: Fragment,
) : PsiBuildProblem(Level.Error, BuildProblemType.InconsistentConfiguration) {

    private val dependency: MavenDependencyBase = declaration.dependency

    override val element: PsiElement
        get() = dependency.extractPsiElement()

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ConflictingMavenDependencyDeclarations

    override val message: @Nls String
        get() = SchemaBundle.message(
            "maven.dependency.has.conflicting.declarations",
            dependency.managementKey,
            leafFragment.name,
            declaration.publicationSemantics.version ?: "unspecified",
            declaration.publicationSemantics.scope.displayName,
            conflictingDeclaration.publicationSemantics.version ?: "unspecified",
            conflictingDeclaration.publicationSemantics.scope.displayName,
        )
}
