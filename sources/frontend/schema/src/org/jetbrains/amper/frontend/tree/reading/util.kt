/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.EnumNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.IntNode
import org.jetbrains.amper.frontend.tree.KeyValue
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.types.SchemaEnumDeclaration
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.render
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.nio.file.Path

context(contexts: Contexts)
internal fun booleanNode(origin: YamlValue.Scalar, value: Boolean) =
    BooleanNode(value, origin.asTrace(), contexts)

context(contexts: Contexts)
internal fun stringNode(origin: YamlValue.Scalar, semantics: SchemaType.StringType.Semantics?, value: String) =
    StringNode(value, semantics, origin.asTrace(), contexts)

context(contexts: Contexts)
internal fun intNode(origin: YamlValue.Scalar, value: Int) =
    IntNode(value, origin.asTrace(), contexts)

context(contexts: Contexts)
internal fun enumNode(origin: YamlValue.Scalar, declaration: SchemaEnumDeclaration, value: String) =
    EnumNode(value, declaration, origin.asTrace(), contexts)

context(contexts: Contexts)
internal fun pathNode(origin: YamlValue.Scalar, value: Path) =
    PathNode(value, origin.asTrace(), contexts)

internal fun YamlValue.Scalar.asPreciseTrace(rangeInTextValue: IntRange): Trace =
    mapToHostElementRange(rangeInTextValue)?.let { PsiTrace(psi, it) } ?: psi.asTrace()

context(contexts: Contexts)
internal fun mapNode(
    origin: YamlValue,
    children: List<KeyValue>,
) = MappingNode(
    children = children,
    declaration = null,
    trace = origin.asTrace(),
    contexts = contexts,
)

context(contexts: Contexts)
internal fun objectNode(
    origin: YamlValue,
    declaration: SchemaObjectDeclaration,
    children: List<KeyValue>,
) = MappingNode(
    children = children,
    declaration = declaration,
    trace = origin.asTrace(),
    contexts = contexts,
)

context(reporter: ProblemReporter)
internal fun reportUnexpectedValue(
    unexpected: YamlValue,
    expectedType: SchemaType,
    renderOnlyNestedTypeSyntax: Boolean = true,
) {
    val typeString by lazy {
        expectedType.render(
            onlyNested = renderOnlyNestedTypeSyntax,
        )
    }
    val valueForMessage = when (unexpected) {
        is YamlValue.Mapping -> "mapping {}"
        is YamlValue.Scalar -> "scalar"
        is YamlValue.Sequence -> "sequence []"
        is YamlValue.UnknownCompound -> "compound value {}"
        is YamlValue.Missing -> {
            reporter.reportMessage(
                MissingValue(
                    source = PsiBuildProblemSource(unexpected.psi),
                    expectedType = expectedType,
                    typeString = typeString,
                )
            )
            return
        }
        is YamlValue.Alias -> {
            reportParsing(unexpected, TreeDiagnosticId.AliasesAreNotSupported, "validation.structure.unsupported.alias")
            return
        }
    }
    reportParsing(
        unexpected, TreeDiagnosticId.TypeMismatch, "validation.types.unexpected.value",
        typeString, valueForMessage,
        type = BuildProblemType.TypeMismatch,
    )
}

context(_: ProblemReporter)
internal fun reportParsing(
    psi: PsiElement,
    diagnosticId: DiagnosticId,
    messageKey: String,
    vararg args: Any?,
    level: Level = Level.Error,
    type: BuildProblemType = BuildProblemType.Generic,
) {
    reportBundleError(
        source = psi.asBuildProblemSource(),
        diagnosticId = diagnosticId,
        messageKey = messageKey,
        arguments = args,
        level = level,
        problemType = type
    )
}

context(_: ProblemReporter)
internal fun reportParsing(
    value: YamlValue,
    diagnosticId: DiagnosticId,
    messageKey: String,
    vararg args: Any?,
    level: Level = Level.Error,
    type: BuildProblemType = BuildProblemType.Generic,
) {
    reportBundleError(
        source = value.psi.asBuildProblemSource(),
        diagnosticId = diagnosticId,
        messageKey = messageKey,
        arguments = args,
        level = level,
        problemType = type
    )
}

context(contexts: Contexts)
fun errorNode(origin: YamlValue, expectedType: SchemaType) = ErrorNode(expectedType, origin.asTrace(), contexts)

// guarantees to include non-compound child elements
internal fun PsiElement.allChildren(): Sequence<PsiElement> = sequence {
    var current: PsiElement? = firstChild
    while (current != null) {
        yield(current)
        current = current.nextSibling
    }
}

internal fun YamlValue.asTrace() = psi.asTrace()

internal fun YamlKeyValue.asTrace() = psi.asTrace()

internal val ParsingConfig.parseReferences: Boolean get() = when (referenceParsingMode) {
    ReferencesParsingMode.Parse -> true
    ReferencesParsingMode.Ignore, ReferencesParsingMode.IgnoreButWarn -> false
}

internal val ParsingConfig.diagnoseReferences: Boolean get() = when (referenceParsingMode) {
    ReferencesParsingMode.Parse, ReferencesParsingMode.IgnoreButWarn -> true
    ReferencesParsingMode.Ignore -> false
}

internal val ParsingConfig.skipUnknownProperties: Boolean get() = when (unknownPropertiesMode) {
    UnknownPropertiesParsingMode.SkipSilently -> true
    UnknownPropertiesParsingMode.BestEffortSilently, UnknownPropertiesParsingMode.BestEffortAndReport -> false
}

internal val ParsingConfig.reportUnknownProperties: Boolean get() = when (unknownPropertiesMode) {
    UnknownPropertiesParsingMode.SkipSilently, UnknownPropertiesParsingMode.BestEffortSilently -> false
    UnknownPropertiesParsingMode.BestEffortAndReport -> true
}
