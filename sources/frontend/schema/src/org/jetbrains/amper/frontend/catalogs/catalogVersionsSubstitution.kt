/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.catalogs

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.api.BuiltinCatalogTrace
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.api.ResolvedReferenceTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.schema.ExternalMavenDependency
import org.jetbrains.amper.frontend.schema.toMavenCoordinates
import org.jetbrains.amper.frontend.tree.Changed
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.KeyValue
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TransformResult
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.TreeTransformer
import org.jetbrains.amper.frontend.tree.copy
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

context(problemReporter: ProblemReporter)
internal fun MappingNode.substituteCatalogDependencies(catalog: VersionCatalog) =
    CatalogVersionsSubstitutor(catalog, problemReporter).transform(this) as? MappingNode ?: this

internal class CatalogVersionsSubstitutor(
    private val catalog: VersionCatalog,
    private val problemReporter: ProblemReporter,
) : TreeTransformer() {
    private val substitutionTypes = mapOf(
        DeclarationOfCatalogDependency to DeclarationOfExternalMavenDependency,
        DeclarationOfUnscopedCatalogDependency to DeclarationOfUnscopedExternalMavenDependency,
        DeclarationOfShadowDependencyCatalog to DeclarationOfShadowDependencyMaven,
    )

    override fun visitMap(node: MappingNode): TransformResult<TreeNode> {
        val substituted = substitutionTypes[node.declaration]
            ?: return super.visitMap(node)

        fun errorNode() = ErrorNode(substituted.toType(), node.trace, node.contexts)

        val catalogKeyProp = node.children.singleOrNull { it.key == "catalogKey" }
        val catalogKeyScalar = catalogKeyProp?.value as? StringNode
            // Something went wrong in the parsing, i.e., already reported - no need to report anything additionally.
            ?: return Changed(errorNode())

        val catalogKey = catalogKeyScalar.value
        val found = context(problemReporter) {
            catalog.findInCatalogWithReport(catalogKey.removePrefix("$"), catalogKeyScalar.trace)
                ?: return Changed(errorNode())
        }

        // Parse coordinates and create new key values.
        val catalogCoordinates = found.toMavenCoordinates()
        val newKeyValues = [
            ExternalMavenDependency::groupId.name to catalogCoordinates.groupId,
            ExternalMavenDependency::artifactId.name to catalogCoordinates.artifactId,
            ExternalMavenDependency::version.name to catalogCoordinates.version?.value,
        ].mapNotNull { [propName, value] ->
            val property = checkNotNull(substituted.getProperty(propName)) {
                "Missing `$propName` property in the dependency type"
            }
            val newNode = StringNode(
                value = value ?: return@mapNotNull null,
                semantics = (property.type as SchemaType.StringType).semantics,
                trace = ResolvedReferenceTrace(
                    description = "from $catalogKey",
                    referenceTrace = catalogKeyScalar.trace,
                    resolvedValue = found,
                ),
                contexts = catalogKeyScalar.contexts,
            )
            KeyValue(catalogKeyProp.keyTrace, newNode, property, catalogKeyProp.trace)
        }
        
        val newChildren = buildList {
            addAll(node.children)
            remove(catalogKeyProp)
            addAll(newKeyValues)
        }
        return Changed(node.copy(children = newChildren, declaration = substituted))
    }
}

/**
 * Get dependency notation by key. Reports on a missing value.
 */
context(problemReporter: ProblemReporter)
private fun VersionCatalog.findInCatalogWithReport(key: String, keyTrace: Trace?): TraceableString? {
    val value = findInCatalog(key)
    if (value == null && keyTrace is PsiTrace) {
        problemReporter.reportBundleError(
            source = keyTrace.psiElement.asBuildProblemSource(),
            diagnosticId = FrontendDiagnosticId.NoCatalogValue,
            messageKey = when {
                // TODO: This is incorrect, as Compose might be actually enabled, but the catalog reference is wrong.
                //  AMPER-5177
                key.startsWith("compose.") -> "compose.is.disabled"
                // TODO: This is incorrect, as Serialization might be actually enabled, but the catalog reference is wrong.
                //  AMPER-5177
                key.startsWith("kotlin.serialization.") -> "kotlin.serialization.is.disabled"
                else -> "no.catalog.value"
            },
            key,
        )
    }
    if (value != null && key == "compose.material3" && keyTrace is PsiTrace) {
        // TODO change to conditional/disabled catalog entry, when it is possible
        checkComposeMaterial3UnknownVersionMapping(value, keyTrace)
    }
    return value
}

/**
 * Reports a warning when `$compose.material3` is used with a Compose version that doesn't have
 * a known Material3 version mapping.
 */
context(problemReporter: ProblemReporter)
private fun checkComposeMaterial3UnknownVersionMapping(value: TraceableString, keyTrace: PsiTrace) {
    val catalogTrace = value.trace as? BuiltinCatalogTrace ?: return
    val versionTrace = catalogTrace.version.trace as? TransformedValueTrace ?: return

    if (versionTrace.description.contains(UNKNOWN_COMPOSE_MATERIAL3_VERSION_DESCRIPTION_PREFIX)) {
        problemReporter.reportMessage(ComposeMaterial3UnknownVersionMappingProblem(keyTrace.psiElement, versionTrace))
    }
}

class ComposeMaterial3UnknownVersionMappingProblem(
    override val element: PsiElement,
    @UsedInIdePlugin val composeVersionTrace: TransformedValueTrace,
) : PsiBuildProblem(
    level = Level.Warning,
    type = BuildProblemType.Generic,
) {
    val composeVersion: String = composeVersionTrace.sourceValue.toString()

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ComposeMaterial3UnknownVersionMapping
    override val message: @Nls String =
        SchemaBundle.message("compose.material3.unknown.version.mapping", composeVersionTrace.sourceValue.toString())
}