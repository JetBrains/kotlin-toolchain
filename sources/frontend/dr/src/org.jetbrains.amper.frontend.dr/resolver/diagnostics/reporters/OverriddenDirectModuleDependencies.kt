/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependency
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.filterGraph
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.orUnspecified
import org.jetbrains.amper.dependency.resolution.originalVersion
import org.jetbrains.amper.dependency.resolution.resolvedVersion
import org.jetbrains.amper.dependency.resolution.transitiveParents
import org.jetbrains.amper.dependency.resolution.version
import org.jetbrains.amper.frontend.api.BuiltinCatalogTrace
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.DerivedValueTrace
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.FrontendDrBundle
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrDiagnosticsReporter
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrReporterContext
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OverriddenDirectModuleDependencies::class.java)

open class OverriddenDirectModuleDependencies : DrDiagnosticsReporter {
    override val level = Level.Warning

    override fun reportBuildProblemsForNode(
        node: DependencyNode,
        problemReporter: ProblemReporter,
        level: Level,
        context: DrReporterContext,
    ) {
        if (node !is DirectFragmentDependencyNode) return
        if (node.isTransitive) return  // only direct dependencies of the module are reported (not transitive ones from other modules this one depende on)
        val dependencyNode = node.dependencyNode

        val originalVersion = dependencyNode.originalVersion() ?: return

        if (originalVersion != dependencyNode.resolvedVersion()) {
            val moduleNode =
                node.transitiveParents().filterIsInstance<ModuleDependencyNode>().singleOrNull { it.topLevel } ?: return
            val moduleName = moduleNode.moduleName
            val isForTestsModule = moduleNode.isForTests
            if (moduleNode.isForTests) return // do not report diagnostic for tests (avoiding double calculation of insights for test/main resolution)

            // We prefer the trace of the coordinates to the trace of the notation as it's more specific.
            // E.g., in the case of implicit dependencies, it prefers 'version' over 'enabled'.
            val psiElement =
                node.notation.coordinates.extractPsiElementOrNull() ?: node.notation.extractPsiElementOrNull()
            // TODO: This is somewhat bad, because if we messed up with traces, the override goes unnoticed and might leave a user perplexed in the runtime.
            if (psiElement != null) {
                val insightsCache = context.cache.computeIfAbsent(insightsCacheKey) { mutableMapOf() }
                val dependencyInsight = insightsCache.computeIfAbsent(
                    DependencyInsightKey(dependencyNode.key, moduleName, isForTestsModule)
                ) {
                    // This call assumes that conflict resolution is applied module-wide (test/main are resolved separately though).
                    // Rule of thumb: this method should be called on the complete (!) subgraph that contains
                    // all nodes resolved with the same conflict resolver.
                    moduleNode.filterGraph(
                        dependencyNode.group,
                        dependencyNode.module,
                        resolvedVersionOnly = true,
                    )
                }
                problemReporter.reportMessage(
                    ModuleDependencyWithOverriddenVersion(
                        node,
                        overrideInsight = dependencyInsight,
                        psiElement
                    )
                )
            }
        }
    }

    companion object {
        private val insightsCacheKey =
            Key<MutableMap<DependencyInsightKey, DependencyNode>>("OverriddenDirectModuleDependencies::insightsCache")
    }
}

private data class DependencyInsightKey(
    val key: Key<MavenDependency>,
    val moduleName: String,
    val isForTests: Boolean,
)

class ModuleDependencyWithOverriddenVersion(
    @field:UsedInIdePlugin
    val originalNode: DirectFragmentDependencyNode,
    @field:UsedInIdePlugin
    val overrideInsight: DependencyNode,
    @field:UsedInIdePlugin
    override val element: PsiElement,
) : PsiBuildProblem(Level.Warning, BuildProblemType.Generic) {
    val dependencyNode: MavenDependencyNode
        get() = originalNode.dependencyNode
    val originalVersion: String
        get() = dependencyNode.originalVersion().orUnspecified()
    val effectiveVersion: String
        get() = dependencyNode.dependency.version.orUnspecified()
    val effectiveCoordinates: String
        get() = dependencyNode.key.name

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.DependencyVersionIsOverridden
    override val message: @Nls String
        get() {
            val implicitDependencyReason = when (val trace = originalNode.notation.trace) {
                is BuiltinCatalogTrace,
                is PsiTrace,
                    -> null
                DefaultTrace -> FrontendDrBundle.message("dependency.added.by.default.reason")
                is DerivedValueTrace -> trace.description
            }

            return when {
                dependencyNode.originalVersion != null -> FrontendDrBundle.message(
                    messageKey = if (implicitDependencyReason != null) "dependency.version.is.overridden.implicit" else "dependency.version.is.overridden",
                    dependencyNode.originalVersion, effectiveCoordinates, effectiveVersion, implicitDependencyReason
                )
                dependencyNode.versionFromBom != null -> FrontendDrBundle.message(
                    messageKey = if (implicitDependencyReason != null) "dependency.version.from.bom.is.overridden.implicit" else "dependency.version.from.bom.is.overridden",
                    dependencyNode.versionFromBom, effectiveCoordinates, effectiveVersion, implicitDependencyReason
                )
                else -> error("Version is not specified, should never happen at this stage")
            }
        }
}
