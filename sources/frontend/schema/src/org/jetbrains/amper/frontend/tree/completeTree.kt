/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.schema.ModuleProduct
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * A property path in a tree with associated value traces.
 * Example: `["settings", "kotlin", "version"]`
 */
private typealias ValuePath = List<String>

/**
 * Convert a refined mapping node into a complete object node, if possible.
 *
 * Incomplete (invalid, unresolved, etc.) nodes are skipped as list/map elements.
 * If encountered in an object, they lead to the whole object being skipped because it'd no longer be complete.
 * If this leads to the root node being skipped, then this routine returns `null`.
 */
context(_: ProblemReporter)
fun RefinedMappingNode.completeTree(): CompleteObjectNode? {
    return ensureCompleteTreeNode(this, emptyList()) as CompleteObjectNode?
}

context(_: ProblemReporter)
private fun ensureCompleteTreeNode(
    node: RefinedTreeNode,
    valuePath: ValuePath,
): CompleteTreeNode? {
    when (node) {
        is ErrorNode,
            // Do not report: reported during parsing
        is ReferenceNode, is StringInterpolationNode,
            -> {
            // Do not report: must be already reported during reference resolution
            return null
        }
        else -> {}
    }

    return when (node) {
        // Already a complete node - return as is
        is CompleteTreeNode -> node
        is RefinedListNode -> {
            // Complete children filtering errors (represented by `null`) out.
            // Nothing to report - they are already reported.
            val completeChildren = node.children.mapNotNull {
                ensureCompleteTreeNode(it, valuePath + "[]")
            }
            CompleteListNode(completeChildren, node.trace, node.contexts)
        }
        is RefinedMappingNode -> when (val declaration = node.declaration) {
            null -> {  // Map
                val completeKeyValues = node.refinedChildren.mapValues { [key, keyValue] ->
                    val completeValue = ensureCompleteTreeNode(
                        keyValue.value,
                        valuePath + key,
                    )
                    completeValue?.let { keyValue.asCompleteForMap(it) }
                }.filterValues { it != null }.mapValues { it.value!! }

                CompleteMapNode(completeKeyValues, node.trace, node.contexts)
            }
            else -> {  // Object
                val completeKeyValues = mutableMapOf<String, CompletePropertyKeyValue>()
                var hasMissingRequiredProps = false
                val missingProperties = mutableListOf<MissingPropertyInfo>()
                for (property in declaration.properties) {
                    val propertyValuePath = valuePath + property.name
                    val mapLikePropertyValue = node.refinedChildren[property.name]
                    propertyCheckDefaultIntegrity(property, mapLikePropertyValue)
                    if (mapLikePropertyValue == null) {
                        // Property is not mentioned at all
                        if (property.isUserSettable) {
                            check(!node.trace.isDefault) {
                                // We should not allow creating objects by default if they have required properties
                                "missing properties in default objects should be not reached"
                            }
                            missingProperties += MissingPropertyInfo(
                                trace = node.trace,
                                valuePath = propertyValuePath,
                                propertyDeclaration = property,
                            )
                        }
                        hasMissingRequiredProps = true
                        continue
                    }
                    val completeChild = ensureCompleteTreeNode(
                        node = mapLikePropertyValue.value,
                        valuePath = propertyValuePath,
                    )
                    if (completeChild == null) {
                        // we don't report here because the error must have been reported when parsing the property
                        hasMissingRequiredProps = true
                        continue
                    }
                    
                    // Property value cannot be converted to `CompletePropertyKeyValue` 
                    // when property was treated as map property at the moment of its creation, thus
                    // being created without declaration.
                    //
                    // For example, `description` set in module template will be treated as unknown map property.
                    val asCompleteValue = mapLikePropertyValue.tryAsCompletePropertyKeyValue(completeChild)
                    
                    // For now, we can skip here safely, since unknown properties are reported, 
                    // except for plugin descriptor files, and those don't have merging
                    // (so we won't end in a situation where there is a valid map value being merged in an object property)
                    if (asCompleteValue != null) {
                        completeKeyValues[property.name] = asCompleteValue
                    }
                }
                // Group missing properties by trace and report once per group
                missingProperties.groupBy { it.trace }.values.forEach { group ->
                    reportMissingProperties(group, declaration)
                }
                if (hasMissingRequiredProps) {
                    // We don't allow incomplete objects
                    null
                } else {
                    CompleteObjectNode(completeKeyValues, declaration, node.trace, node.contexts)
                }
            }
        }
    }
}

context(reporter: ProblemReporter)
private fun reportMissingProperties(
    properties: List<MissingPropertyInfo>,
    inside: SchemaObjectDeclaration,
) {
    val reportedIndividually = mutableSetOf<MissingPropertyInfo>()
    when (inside) {
        is DeclarationOfMinimalModule -> for (info in properties) {
            if (info.valuePath == listOf(MinimalModule::product.name)) {
                reporter.reportBundleError(
                    source = info.trace.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.ProductNotDefined,
                    messageKey = "product.not.defined.empty",
                )
                reportedIndividually += info
            }
        }
        is DeclarationOfModuleProduct -> for (info in properties) {
            if (info.valuePath == listOf(ModuleProduct::type.name)) {
                reporter.reportBundleError(
                    source = info.trace.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.ProductNotDefined,
                    messageKey = "product.not.defined",
                )
                reportedIndividually += info
            }
        }
    }
    val remainingBatch = properties - reportedIndividually
    if (remainingBatch.isNotEmpty()) {
        reporter.reportMessage(MissingPropertiesProblem(remainingBatch, inside))
    }
}

/**
 * Information about a single missing required property.
 * @see MissingPropertiesProblem
 */
class MissingPropertyInfo(
    /**
     * The trace of the outermost explicitly present (non-default) construct.
     */
    val trace: Trace,
    /**
     * The path (from the document root) for which the value is missing.
     * E.g., `["product", "type"]` or `["repositories", "[]", "url"]"`
     */
    val valuePath: List<String>,
    /**
     * the declaration of the missing property.
     */
    val propertyDeclaration: SchemaObjectDeclaration.Property,
) {
    @Deprecated(
        level = DeprecationLevel.ERROR,
        message = "Use propertyDeclaration.name instead",
        replaceWith = ReplaceWith("listOf(propertyDeclaration.name)")
    )
    val relativeValuePath: List<String>
        get() = listOf(propertyDeclaration.name)
}

private fun propertyCheckDefaultIntegrity(
    propertyDeclaration: SchemaObjectDeclaration.Property,
    pValue: KeyValue?,
) {
    check(propertyDeclaration.default == null || pValue != null) {
        "A property ${propertyDeclaration.name} has a default ${propertyDeclaration.default}, " +
                "but the value is missing nevertheless. " +
                "This is a sign that the default was not properly added on the tree level. " +
                "Please check that defaults are correctly appended for this tree."
    }
}
