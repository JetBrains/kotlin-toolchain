/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.contexts.PlatformCtx
import org.jetbrains.amper.frontend.contexts.TestCtx
import org.jetbrains.amper.frontend.schema.SchemaMavenCoordinates
import org.jetbrains.amper.frontend.tree.KeyValue
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.copy
import org.jetbrains.amper.frontend.tree.reading.maven.validateAndReportMavenCoordinates
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.render
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.ProblemReporter

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
internal fun parseObject(
    value: YamlValue,
    type: SchemaType.ObjectType,
    allowTypeTag: Boolean = false,
): TreeNode {
    if (!allowTypeTag) {
        value.tag?.let { tag ->
            if (!tag.text.startsWith("!!")) {  // Standard "!!" tags are reported in `parseValue`
                reportParsing(tag, TreeDiagnosticId.TagsAreNotSupported, "validation.structure.unsupported.tag")
            }
        }
    }

    val declaration = type.declaration
    val fromKeyProperty = declaration.getFromKeyAndTheRestNestedProperty()
    return if (fromKeyProperty != null) {
        parseObjectWithCustomKeyParsing(value, type) { key, keyValueTrace ->
            val argumentType = fromKeyProperty.type as SchemaType.ScalarType // should be scalar by design
            listOf(
                KeyValue(
                    key.asTrace(),
                    value = parseNode(key, argumentType),
                    propertyDeclaration = fromKeyProperty,
                    trace = keyValueTrace,
                )
            )
        }
    } else if (declaration.isExternalDependencyNotation) {
        if (value is YamlValue.Mapping && tryInferTypeFromKnownKeys(value) == DependencyTypeInferenceResult.Maven) {
            // If we can infer Maven type from the available keys, then we should parse this YAML value as plain object...
            parseObjectWithoutFromKeyProperty(value, type)
        } else {
            //... Otherwise, we should treat this YAML value as a single key coordinates notation with some other dependency attributes.
            parseObjectWithCustomKeyParsing(value, type) { key, keyValueTrace ->
                if (key !is YamlValue.Scalar) {
                    reportParsing(
                        key,
                        TreeDiagnosticId.UnexpectedValue,
                        "validation.expected",
                        SchemaType.StringType().render(),
                        type = BuildProblemType.TypeMismatch
                    )
                    null
                } else {
                    parseMavenCoordinates(declaration, key, keyValueTrace)
                }
            }
        }
    } else {
        parseObjectWithoutFromKeyProperty(value, type)
    }
}

context(_: Contexts, _: ParsingConfig, reporter: ProblemReporter)
private fun parseMavenCoordinates(
    declaration: SchemaObjectDeclaration,
    key: YamlValue.Scalar,
    keyValueTrace: Trace,
): List<KeyValue>? {
    if (!validateAndReportMavenCoordinates(key.psi, key.textValue)) return null
    val coordsAndPackaging = key.textValue.split("@")
    val coordinatesParts = coordsAndPackaging.first().split(":")
    val packagingType = coordsAndPackaging.getOrNull(1)
    val groupId = coordinatesParts[0]
    val artifactId = coordinatesParts[1]
    val version = coordinatesParts.getOrNull(2)
    val classifier = coordinatesParts.getOrNull(3)
    return listOf(
        groupId to SchemaMavenCoordinates::groupId.name,
        artifactId to SchemaMavenCoordinates::artifactId.name,
        version to SchemaMavenCoordinates::version.name,
        classifier to SchemaMavenCoordinates::classifier.name,
        packagingType to SchemaMavenCoordinates::packagingType.name,
    ).mapNotNull { [keyValue, keyName] ->
        KeyValue(
            key = keyName,
            keyTrace = key.asTrace(),
            value = keyValue?.let { stringNode(key, null, it) }
                ?: return@mapNotNull null,
            parentType = declaration,
            trace = keyValueTrace,
        )
    }
}

internal typealias CustomKeyParser =
        context(Contexts, ParsingConfig, ProblemReporter)
            (key: YamlValue, keyValueTrace: Trace) -> List<KeyValue>?

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
private fun parseObjectWithCustomKeyParsing(
    value: YamlValue,
    type: SchemaType.ObjectType,
    customKeyParser: CustomKeyParser,
): TreeNode {
    val otherProperties = type.declaration.properties.filterNot { it.isFromKeyAndTheRestNested }
    return when (value) {
        is YamlValue.Mapping if otherProperties.isNotEmpty() -> {
            val argKeyValue = value.keyValues.singleOrNull() ?: run {
                reportParsing(
                    value,
                    TreeDiagnosticId.MappingShouldHaveSingleKeyValue,
                    "validation.types.invalid.ctor.arg.key",
                    type.render()
                )
                return errorNode(value, type)
            }
            val keyValuesFromKey = customKeyParser(argKeyValue.key, value.asTrace())
            // If we can't parse the key properly, then we should treat it as an error.    
                ?: return errorNode(value, type)
            val nestedRemainingObject = argKeyValue.value
            val remainingProperties = parseObjectWithoutFromKeyProperty(nestedRemainingObject, type)as? MappingNode
                ?: return errorNode(value, type)
            remainingProperties.copy(
                children = (keyValuesFromKey) + remainingProperties.children,
                trace = argKeyValue.asTrace(),
            )
        }
        is YamlValue.Scalar -> {
            val keyValuesFromKey = customKeyParser(value, value.asTrace())
            // If we can't parse the key properly, then we should treat it as an error.
                ?: return errorNode(value, type)
            objectNode(
                children = keyValuesFromKey,
                origin = value,
                declaration = type.declaration,
            )
        }
        else -> {
            // `renderOnlyNestedTypeSyntax` is needed to include the `(prop | prop: (...))` syntax.
            reportUnexpectedValue(value, type, renderOnlyNestedTypeSyntax = false)
            errorNode(value, type)
        }
    }
}

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
private fun parseObjectWithoutFromKeyProperty(value: YamlValue, type: SchemaType.ObjectType): TreeNode {
    return when (value) {
        is YamlValue.Mapping -> parseObjectFromMap(value, type)
        is YamlValue.Scalar -> parseObjectFromScalarShorthand(value, type)
        is YamlValue.Sequence -> parseObjectFromListShorthand(value, type)
        else -> {
            reportUnexpectedValue(value, type)
            errorNode(value, type)
        }
    }
}

context(contexts: Contexts, config: ParsingConfig, reporter: ProblemReporter)
private fun parseObjectFromMap(value: YamlValue.Mapping, type: SchemaType.ObjectType): MappingNode {
    fun parseObjectProperty(keyValue: YamlKeyValue): KeyValue? {
        val key = keyValue.key
        val [propertyName, propertyContexts] = parsePropertyKeyContexts(key)
            ?: return null
        val property = type.declaration.getProperty(propertyName)
            ?.takeUnless { it.isFromKeyAndTheRestNested }
        if (property == null) {
            if (config.skipUnknownProperties) {
                return null
            }

            // Try to parse as much as possible on the best-effort basis
            return KeyValue(
                keyTrace = key.asTrace(),
                trace = keyValue.asTrace(),
                key = propertyName,
                value = parseNodeFromKeyValue(
                    keyValue = keyValue,
                    type = SchemaType.UndefinedType,
                    explicitContexts = propertyContexts,
                ),
            )
        }
        if (!property.isUserSettable) {
            reportParsing(
                key,
                TreeDiagnosticId.PropertyIsNotSettable,
                "validation.property.not.settable",
                property.name
            )
            return null
        }
        return KeyValue(
            keyTrace = key.asTrace(),
            trace = keyValue.asTrace(),
            value = parseNodeFromKeyValue(keyValue, property.type, explicitContexts = propertyContexts),
            propertyDeclaration = property,
        )
    }

    return objectNode(
        children = value.keyValues.mapNotNull { keyValue ->
            parseObjectProperty(keyValue)
        },
        origin = value,
        declaration = type.declaration,
    )
}

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
private fun parseObjectFromScalarShorthand(
    scalar: YamlValue.Scalar,
    type: SchemaType.ObjectType,
): TreeNode {
    fun parseScalarShorthandValue(): Pair<SchemaObjectDeclaration.Property, TreeNode>? {
        val boolean = type.declaration.getBooleanShorthand()
        val secondary = type.declaration.getSecondaryShorthand()

        if (boolean != null && scalar.textValue == boolean.name) {
            return boolean to booleanNode(scalar, true)
        }
        return when (val type = secondary?.type) {
            is SchemaType.EnumType -> secondary to parseEnum(
                // additionalSuggestedValues is specified
                // to include the boolean shorthand in the report if parsing fails.
                scalar, type, additionalSuggestedValues = listOfNotNull(boolean?.name),
            )
            is SchemaType.ScalarType -> secondary to parseScalar(scalar, type)
            else -> null
        }
    }

    val [property, result] = parseScalarShorthandValue() ?: run {
        reportUnexpectedValue(scalar, type)
        return errorNode(scalar, type)
    }

    val trace = scalar.asTrace()
    return objectNode(
        children = listOf(
            KeyValue(
                keyTrace = trace,
                trace = trace,
                value = result,
                propertyDeclaration = property,
            )
        ),
        declaration = type.declaration,
        origin = scalar,
    )
}

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
private fun parseObjectFromListShorthand(
    sequence: YamlValue.Sequence,
    type: SchemaType.ObjectType,
): TreeNode {
    val listShorthandProperty = type.declaration.getSecondaryShorthand()?.takeIf { it.type is SchemaType.ListType }

    if (listShorthandProperty != null) {
        val propertyType = listShorthandProperty.type as SchemaType.ListType
        // At this point we are committed to read this as a shorthand, so
        val trace = sequence.asTrace()
        return objectNode(
            children = listOfNotNull(
                KeyValue(
                    keyTrace = trace,
                    trace = trace,
                    value = parseList(sequence, propertyType),
                    propertyDeclaration = listShorthandProperty,
                )
            ),
            declaration = type.declaration,
            origin = sequence,
        )
    }
    // shorthand is unsupported
    reportUnexpectedValue(sequence, type)
    return errorNode(sequence, type)
}

context(_: Contexts, config: ParsingConfig, _: ProblemReporter)
internal fun parsePropertyKeyContexts(
    key: YamlValue,
): Pair<String, Contexts>? {
    val keyText = context(EmptyContexts) {
        parseScalarKey(key, SchemaType.StringType) as StringNode? ?: return null
    }.value
    if (config.supportContexts) {
        val context = keyText.indexOf('@').takeUnless { it == -1 }?.let { keyText.substring(it + 1) }
        val keyWithoutContext = if (context != null) keyText.substringBefore('@') else keyText
        if (context != null && '+' in context) {
            reportParsing(
                key,
                TreeDiagnosticId.MultipleQualifiersAreNotSupported,
                "multiple.qualifiers.are.unsupported"
            )
            return null
        }
        val [mappedName, requiresTestContext] = when (keyWithoutContext) {
            "test-settings" -> "settings" to true
            "test-dependencies" -> "dependencies" to true
            else -> keyWithoutContext to false
        }
        val trace = key.psi.asTrace()
        return mappedName to buildSet {
            if (requiresTestContext) add(TestCtx(trace))
            if (context != null) add(PlatformCtx(context, trace))
        }
    } else {
        // TODO: Reserve context syntax for the future?
        return keyText to EmptyContexts
    }
}
