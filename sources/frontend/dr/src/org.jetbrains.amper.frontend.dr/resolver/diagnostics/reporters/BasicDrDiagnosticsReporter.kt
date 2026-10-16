/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.UnsupportedCapabilities
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.version
import org.jetbrains.amper.frontend.api.BuiltinCatalogTrace
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.DerivedValueTrace
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.api.Traceable
import org.jetbrains.amper.frontend.api.TraceableVersion
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.FrontendDrBundle
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrDiagnosticsReporter
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrReporterContext
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.mapLevelToSeverity
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.mapSeverityToLevel
import org.jetbrains.amper.frontend.dr.resolver.fragmentDependencies
import org.jetbrains.amper.frontend.getLineAndColumnRangeInPsiFile
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.problems.reporting.BuildProblemImpl
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls
import java.io.File

object BasicDrDiagnosticsReporter : DrDiagnosticsReporter {
    override val level = Level.Error

    override fun reportBuildProblemsForNode(
        node: DependencyNode,
        problemReporter: ProblemReporter,
        level: Level,
        context: DrReporterContext,
    ) {
        val severity = level.mapLevelToSeverity()
        val importantMessages = node.messages.filter { it.severity >= severity && it.toString().isNotBlank() }

        if (importantMessages.isEmpty()) return

        if (node.fragmentDependencies.isEmpty()) {
            reportMessagesAsGlobal(importantMessages, problemReporter)
        } else {
            reportMessagesOnParentPsiNodes(node, importantMessages, problemReporter, context)
        }
    }

    @OptIn(NonIdealDiagnostic::class)
    private fun reportMessagesAsGlobal(importantMessages: List<Message>, problemReporter: ProblemReporter) {
        for (message in importantMessages) {
            val msgLevel = message.mapSeverityToLevel()
            val buildProblem = BuildProblemImpl(
                diagnosticId = FrontendDiagnosticId.DependencyResolutionProblem,
                source = GlobalBuildProblemSource,
                message = message.detailedMessage,
                level = msgLevel,
                type = BuildProblemType.Generic,
            )
            problemReporter.reportMessage(buildProblem)
        }
    }

    private fun reportMessagesOnParentPsiNodes(
        node: DependencyNode,
        importantMessages: List<Message>,
        problemReporter: ProblemReporter,
        context: DrReporterContext,
    ) {
        for (directDependency in node.fragmentDependencies) {
            // for every direct module dependency referencing this dependency node
            val psiElement = directDependency.notation.coordinates.trace.extractPsiElementOrNull()

            if (psiElement != null) {
                val alreadyReported = context.cache.computeIfAbsent(alreadyReportedCacheKey) { mutableSetOf() }
                if (!alreadyReported.add(AlreadyReportedKey(node, psiElement))) continue

                for (message in importantMessages) {
                    val msgLevel = message.mapSeverityToLevel()
                    if (node.isTransitiveFor(directDependency)) {
                        if (!message.reportTransitive) continue
                        // If the message is already reported on the direct dependency node,
                        // skip reporting it on the transitive node.
                        if (directDependency.dependencyNode.messages.any { it.id == message.id }) continue
                    }

                    val buildProblem = DependencyBuildProblem(
                        problematicDependency = node,
                        directFragmentDependency = directDependency,
                        errorMessage = message,
                        level = msgLevel,
                        element = psiElement,
                        versionDefinition = findVersionDefinition(node, directDependency, psiElement),
                    )
                    problemReporter.reportMessage(buildProblem)
                }
            }
        }
    }

    /**
     * Returns the definition of the version of the [node] if it is defined not in the same place
     * where the [directDependency] itself (e.g., the version comes from the settings).
     *
     * @param node Dependency node that represents the problematic dependency in the graph
     * @param directDependency Dependency node that references the [node] from the module file
     * @param dependencyElement PSI element trace of [directDependency]
     */
    private fun findVersionDefinition(
        node: DependencyNode,
        directDependency: DirectFragmentDependencyNode,
        dependencyElement: PsiElement,
    ): VersionDefinition? {
        if (node !is MavenDependencyNode) return null
        
        val resolvedVersion = directDependency.notation.coordinates.version?.findTraceableVersion()
            // Try to find resolvedVersion through version fields first.   
            ?: directDependency.notation.coordinates.findTraceableVersion() 
            ?: return null

        val versionTrace = resolvedVersion.trace

        if (versionTrace !is PsiTrace) return null
        if (versionTrace == directDependency.notation.trace) return null

        if (resolvedVersion.value != node.dependency.version) return null

        val dependencyVirtualFile = dependencyElement.containingFile?.virtualFile ?: return null
        val versionFile = versionTrace.psiElement.containingFile?.virtualFile ?: return null

        val relativePath = VfsUtilCore.findRelativePath(dependencyVirtualFile, versionFile, File.separatorChar)?.takeIf {
            dependencyVirtualFile != versionFile
        } ?: versionTrace.psiElement.containingFile.name
        return VersionDefinition(versionTrace.psiElement, relativePath)
    }

    private fun Traceable.findTraceableVersion(): TraceableVersion? {
        if (this is TraceableVersion) {
            return this
        }
        return when (val t = trace) {
            is DerivedValueTrace -> t.sourceValue.findTraceableVersion()
            is BuiltinCatalogTrace -> t.version
            is DefaultTrace -> null
            is PsiTrace -> null
        }
    }

    private val alreadyReportedCacheKey =
        Key<MutableSet<AlreadyReportedKey>>("DependencyNode::alreadyReported")
}

private data class AlreadyReportedKey(
    val node: DependencyNode,
    val psiElement: PsiElement,
)

class DependencyBuildProblem(
    @field:UsedInIdePlugin
    val problematicDependency: DependencyNode,
    val directFragmentDependency: DirectFragmentDependencyNode,
    val errorMessage: Message,
    level: Level,
    @field:UsedInIdePlugin
    override val element: PsiElement,
    @field:UsedInIdePlugin
    val versionDefinition: VersionDefinition?,
) : PsiBuildProblem(level, BuildProblemType.Generic) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.DependencyResolutionProblem

    override val message: @Nls String
        get() = buildString {
            if (!isTransitive) {
                append(errorMessage.detailedMessage)
            } else {
                append(
                    FrontendDrBundle.message(
                        messageKey = "dependency.problem.transitive",
                        directFragmentDependency.dependencyNode,
                        problematicDependency,
                        errorMessage.detailedMessage,
                    )
                )
            }
            val versionDefinitionMessage = getVersionDefinitionMessage()
            if (versionDefinitionMessage != null) {
                appendLine()
                appendLine()
                append(versionDefinitionMessage)
            }
            val exclusionHint = getExclusionHint()
            if (exclusionHint != null) {
                appendLine()
                appendLine()
                append(exclusionHint)
            }
        }

    val isTransitive: Boolean
        get() = problematicDependency.isTransitiveFor(directFragmentDependency)

    /**
     * A transitive dependency that can't be used at all might be not needed by the module,
     * then excluding it is a way to get rid of the problem.
     */
    private fun getExclusionHint(): @Nls String? {
        if (errorMessage.id != UnsupportedCapabilities.id || !isTransitive) return null
        val node = problematicDependency as? MavenDependencyNode ?: return null
        return FrontendDrBundle.message("dependency.problem.exclusion.hint", "${node.group}:${node.module}")
    }

    private fun getVersionDefinitionMessage(): @Nls String? {
        versionDefinition ?: return null
        val node = problematicDependency as? MavenDependencyNode ?: return null
        val range = getLineAndColumnRangeInPsiFile(versionDefinition.versionElement)

        return FrontendDrBundle.message(
            "dependency.problem.version.definition",
            node.dependency.version,
            "${versionDefinition.relativePath}:${range.start.line}:${range.start.column}",
        )
    }
}

/**
 * Information about where the version of the dependency is defined.
 *
 * @param versionElement PSI element containing the version definition
 * @param relativePath Relative path from the file containing dependency to the file containing the version definition
 * @see BasicDrDiagnosticsReporter.findVersionDefinition
 */
data class VersionDefinition(val versionElement: PsiElement, val relativePath: String)

internal fun DependencyNode.isTransitiveFor(fragmentDependency: DirectFragmentDependencyNode): Boolean =
    this != fragmentDependency && fragmentDependency !in parents
