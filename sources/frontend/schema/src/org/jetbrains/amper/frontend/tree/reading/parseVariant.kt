/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.schema.SchemaMavenCoordinates
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Bom
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Catalog
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Failed
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Local
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.LocalSwiftPackage
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Maven
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.SwiftPackage
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.SchemaVariantDeclaration
import org.jetbrains.amper.frontend.types.TaskActionVariantDeclaration
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Try to derive the concrete type from the value based on the rules for the given [type]
 * and then call the corresponded concrete parsing routine with the derived type.
 */
context(_: Contexts, _: ParsingConfig, reporter: ProblemReporter)
internal fun parseVariant(
    value: YamlValue,
    type: SchemaType.VariantType,
): TreeNode = when (type.declaration) {
    // Do not parse directly, delegate to another branch for composability and DRY
    DeclarationOfVariantDependency -> when (inferDependencyType(value, isScoped = true)) {
        Bom -> parseObject(value, type.checkSubType(DeclarationOfBomDependency))
        LocalSwiftPackage -> parseObject(value, type.checkSubType(DeclarationOfLocalSwiftPMDependencySchema))
        SwiftPackage -> parseObject(value, type.checkSubType(DeclarationOfRemoteSwiftPMDependencySchema))
        Failed -> {
            reportParsing(
                value,
                TreeDiagnosticId.WrongDependencyFormat,
                "validation.types.dependency.wrong.syntax.scoped"
            )
            errorNode(value, type)
        }
        else -> parseVariant(value, type.checkSubType(DeclarationOfVariantScopedDependency))
    }

    DeclarationOfVariantScopedDependency -> when (inferDependencyType(value, isScoped = true)) {
        Local -> parseObject(value, type.checkSubType(DeclarationOfInternalDependency))
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfCatalogDependency))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfExternalMavenDependency))
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        LocalSwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        SwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(
                value,
                TreeDiagnosticId.WrongDependencyFormat,
                "validation.types.dependency.wrong.syntax.scoped.no.bom"
            )
            errorNode(value, type)
        }
    }

    DeclarationOfVariantUnscopedDependency -> when (inferDependencyType(value, isScoped = false)) {
        Local -> parseObject(value, type.checkSubType(DeclarationOfUnscopedModuleDependency))
        // Do not parse directly, delegate to another branch for composability and DRY
        Maven, Catalog -> parseVariant(value, type.checkSubType(DeclarationOfVariantUnscopedExternalDependency))
        Bom -> parseObject(value, type.checkSubType(DeclarationOfUnscopedBomDependency))
        LocalSwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        SwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(
                value,
                TreeDiagnosticId.WrongDependencyFormat,
                "validation.types.dependency.wrong.syntax.unscoped"
            )
            errorNode(value, type)
        }
    }

    DeclarationOfVariantUnscopedExternalDependency -> when (inferDependencyType(value, isScoped = false)) {
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfUnscopedCatalogDependency))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfUnscopedExternalMavenDependency))
        Local -> {
            reportParsing(value, TreeDiagnosticId.LocalDependenciesAreNotSupported, "unexpected.local.module")
            errorNode(value, type)
        }
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        LocalSwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        SwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(
                value,
                TreeDiagnosticId.WrongDependencyFormat,
                "validation.types.dependency.wrong.syntax.unscoped.external"
            )
            errorNode(value, type)
        }
    }

    DeclarationOfVariantShadowDependency -> when (inferDependencyType(value, isScoped = false)) {
        Local -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyLocal))
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyCatalog))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyMaven))
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        LocalSwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        SwiftPackage -> {
            reportParsing(value, TreeDiagnosticId.SwiftPMDependencyIsNotSupported, "unexpected.swift.package")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(
                value,
                TreeDiagnosticId.WrongDependencyFormat,
                "validation.types.dependency.wrong.syntax.unscoped.no.bom"
            )
            errorNode(value, type)
        }
    }
    is TaskActionVariantDeclaration -> {
        val tag = value.tag
        if (tag == null) {
            reporter.reportMessage(MissingTaskActionType(element = value.psi, taskActionType = type.declaration))
            return errorNode(value, type)
        }
        val requestedTypeName = tag.text.removePrefix("!")
        val variant = type.declaration.variants.find { it.qualifiedName == requestedTypeName }
        if (variant == null) {
            reporter.reportMessage(
                InvalidTaskActionType(
                    element = tag,
                    invalidType = requestedTypeName,
                    taskActionType = type.declaration,
                )
            )
            errorNode(value, type)
        } else {
            parseObject(value, variant.toType(), allowTypeTag = true)
        }
    }
    else -> {
        // NOTE: When (if) we support user-defined sealed classes based on type tags,
        // replace the error with a meaningful description
        error("Unhandled variant type: ${type.declaration.qualifiedName}")
    }
}

private val gavDiscriminatorFields = setOf(
    SchemaMavenCoordinates::artifactId.name,
    SchemaMavenCoordinates::groupId.name,
)

internal enum class DependencyTypeInferenceResult {
    /** Starts from `.` */
    Local,

    /** Starts from `$` */
    Catalog,

    /** Maven coordinates as string or in the full form **/
    Maven,

    /** has a single 'bom' property **/
    Bom,

    /** Swift package on the file system **/
    LocalSwiftPackage,

    /** Swift package from a repository or a Swift Package Registry **/
    SwiftPackage,

    /** type inference is failed - the value has no chance to be correctly parsed as any type */
    Failed,
}

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
private fun inferDependencyType(psi: YamlValue, isScoped: Boolean): DependencyTypeInferenceResult = when (psi) {
    is YamlValue.Mapping ->
        // First, try to find known keys.
        tryInferTypeFromKnownKeys(psi)
        // Then try to infer a type from the key text value. Fail if we know dependency can't have scope (thus only [YamlValue.Scalar] should be here).
            ?: (if (isScoped) psi.keyValues.singleOrNull()?.key?.psi?.text?.let { inferDependencyTypeFromStringKey(it) } else Failed)
            ?: Failed
    is YamlValue.Scalar -> inferDependencyTypeFromStringKey(psi.textValue)
    else -> Failed
}

// !Warning! Sets in internal maps must have no intersections.
private val knownPropertiesDiscriminatedTypes = mapOf(
    gavDiscriminatorFields to Maven,
    setOf("bom") to Bom,
)

/**
 * Try to infer the type from the keys that are available in the YAML mapping.
 */
context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
internal fun tryInferTypeFromKnownKeys(value: YamlValue.Mapping): DependencyTypeInferenceResult? {
    val yamlKeys = value.keyValues.mapNotNull { parsePropertyKeyContexts(it.key)?.first }.toSet()
    return knownPropertiesDiscriminatedTypes.entries
        .firstOrNull { [uniqueKeys, _] -> yamlKeys.any { it in uniqueKeys } }
        ?.value
}

private fun inferDependencyTypeFromStringKey(keyText: String) = when (keyText.firstOrNull()) {
    '$' -> Catalog
    '.' -> Local
    '/' -> Local
    else -> when (keyText) {
        "localSwiftPackage" -> LocalSwiftPackage
        "swiftPackage" -> SwiftPackage
        else -> Maven
    }
}

private fun SchemaType.VariantType.checkSubType(leaf: SchemaObjectDeclaration): SchemaType.ObjectType {
    require(declaration.variantTree.any { it.declaration == leaf }) {
        "Leaf variant declaration \"${leaf.qualifiedName}\" not found in variant tree of \"${declaration.qualifiedName}\""
    }
    return leaf.toType()
}

private fun SchemaType.VariantType.checkSubType(sub: SchemaVariantDeclaration): SchemaType.VariantType {
    require(declaration.variantTree.any { it.declaration == sub }) {
        "Leaf variant declaration \"${sub.qualifiedName}\" not found in variant tree of \"${declaration.qualifiedName}\""
    }
    return sub.toType()
}