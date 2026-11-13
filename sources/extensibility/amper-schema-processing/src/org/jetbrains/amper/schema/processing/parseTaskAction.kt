/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.jetbrains.amper.plugins.schema.model.InputOutputMark
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.diagnostics.KotlinSchemaBuildProblem
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier

context(session: KaSession, _: DiagnosticsReporter, _: SymbolsCollector, _: DeclarationsProvider, _: ParsingOptions)
internal fun parseTaskAction(function: KtNamedFunction): PluginData.TaskInfo? {
    if (!function.isTopLevel) return null.also { report(function, KotlinSchemaBuildProblem::TaskActionNotToplevel) }
    val nameIdentifier = function.nameIdentifier ?: return null // invalid Kotlin (top-level functions are named)
    val name = function.fqName ?: return null  // invalid Kotlin (top-level functions are named)
    when (with(session) { function.symbol }.visibility) {
        KaSymbolVisibility.PUBLIC, KaSymbolVisibility.UNKNOWN -> Unit // okay/ignore
        else -> report(function.visibilityModifier() ?: nameIdentifier, KotlinSchemaBuildProblem::TaskActionMustBePublic)
    }
    function.extensionReceiver()?.let { report(it, KotlinSchemaBuildProblem::ForbiddenTaskActionExtension) }
    function.modifierList?.let { modifiers ->
        modifiers.getModifier(KtTokens.SUSPEND_KEYWORD)?.let {
            report(it, KotlinSchemaBuildProblem::ForbiddenTaskActionSuspend)
        }
        modifiers.getModifier(KtTokens.INLINE_KEYWORD)?.let {
            report(it, KotlinSchemaBuildProblem::ForbiddenTaskActionInline)
        }
    }
    function.typeParameterList?.let { report(it, KotlinSchemaBuildProblem::ForbiddenTaskActionGeneric) }
    function.modifierList?.contextParameterList?.let {
        report(it, KotlinSchemaBuildProblem::ForbiddenTaskActionContextParameters)
    }

    val returnType = with(session) { function.returnType }
    if (with(session) { !returnType.isUnitType }) {
        report(function.typeReference ?: function, KotlinSchemaBuildProblem::ForbiddenTaskActionReturn)
    }

    val properties = function.valueParameters.mapNotNull {
        parseTaskParameter(
            parameter = it,
            docProvider = { name -> function.docComment?.findSectionByTag(KDocKnownTag.PARAM, name)?.getContent() },
        )
    }

    val optOutOfExecutionAvoidance = with(session) { function.symbol }.annotations
        .first { it.classId == TASK_ACTION_ANNOTATION_CLASS }
        .arguments.find { it.name == TASK_ACTION_EXEC_AVOIDANCE_PARAM }
        .let {
            when (val value = it?.expression) {
                is KaAnnotationValue.EnumEntryValue -> value.callableId == EXEC_AVOIDANCE_DISABLED
                else -> false
            }
        }

    return PluginData.TaskInfo(
        syntheticType = PluginData.ClassData(
            name = PluginData.SchemaName(
                packageName = name.parent().pathSegments().joinToString("."),
                simpleNames = listOf(name.shortName().asString()),
            ),
            properties = properties,
            doc = function.getDefaultDocString(),
            origin = nameIdentifier.getSourceLocation(),
        ),
        jvmFunctionName = function.name!!,  // FIXME: How to take JvmName into account here?
        jvmFunctionClassName = @OptIn(KaExperimentalApi::class) with(session) {
            function.symbol.containingJvmClassName
        }!!,  // TODO: Fill this only for backend
        optOutOfExecutionAvoidance = optOutOfExecutionAvoidance,
    )
}

context(session: KaSession, _: DiagnosticsReporter, _: SymbolsCollector, _: DeclarationsProvider, _: ParsingOptions)
private fun parseTaskParameter(
    parameter: KtParameter,
    docProvider: (name: String) -> String?,
): PluginData.ClassData.Property? {
    val parameterName = parameter.name ?: return null // invalid Kotlin
    val typeReference = parameter.typeReference ?: return null // invalid Kotlin
    val parameterSymbol = with(session) { parameter.symbol }
    val type = parameterSymbol.returnType.parseSchemaType(origin = { typeReference }) ?: return null
    val inputMark = parameterSymbol.getAnnotation(INPUT_ANNOTATION_CLASS)
    val outputMark = parameterSymbol.getAnnotation(OUTPUT_ANNOTATION_CLASS)
    val inputOutputMark: InputOutputMark? = if (type.mustBeInputOutputMarked()) {
        when {
            inputMark != null && outputMark != null -> {  // both
                report(parameter, KotlinSchemaBuildProblem::TaskActionParameterPathConflicting)
                null
            }
            inputMark != null -> {
                val inferTaskDependency = inputMark.arguments
                    .find { it.name == INFER_TASK_DEPENDENCY_PARAM }?.expression
                    ?.let { it as? KaAnnotationValue.ConstantValue }
                    ?.value?.value
                if (inferTaskDependency == false) {
                    InputOutputMark.InputNoDependencyInference
                } else {
                    InputOutputMark.Input
                }
            }
            outputMark != null -> InputOutputMark.Output  // output only
            else -> {  // none
                report(parameter, KotlinSchemaBuildProblem::TaskActionParameterPathUnmarked)
                null
            }
        }
    } else {
        if (outputMark != null) report(outputMark.psi(), KotlinSchemaBuildProblem::TaskActionParameterNotPath)
        if (inputMark != null) report(inputMark.psi(), KotlinSchemaBuildProblem::TaskActionParameterNotPath)
        null
    }

    val default = parameter.defaultValue?.let { defaultValue ->
        parseDefaultExpression(defaultValue, type)
    }

    return PluginData.ClassData.Property(
        name = parameterName,
        type = type,
        default = default,
        doc = docProvider(parameterName),
        inputOutputMark = inputOutputMark,
        origin = parameter.getSourceLocation(),
    )
}

context(resolver: DeclarationsProvider)
private fun PluginData.Type.mustBeInputOutputMarked(
    // The names we have already seen (gray in DFS terms) - needed to break out of self-referencing/cyclic types
    seen: Set<PluginData.SchemaName> = emptySet()
): Boolean = when(this) {
    is PluginData.Type.ListType -> elementType.mustBeInputOutputMarked(seen)
    is PluginData.Type.MapType -> valueType.mustBeInputOutputMarked(seen)
    is PluginData.Type.ObjectType -> {
        if (declaration.name in seen) return false
        val seen = seen + declaration.name
        declaration.properties.any {
            if (it.inputOutputMark == InputOutputMark.ValueOnly) {
                return@any false
            }
            it.type.mustBeInputOutputMarked(seen)
        }
    }
    is PluginData.Type.VariantType -> {
        if (declaration.name in seen) return false
        val seen = seen + declaration.name
        declaration.variants.any { it.mustBeInputOutputMarked(seen) }
    }
    is PluginData.Type.PathType -> true
    else -> false
}

private fun KaAnnotation.psi() = checkNotNull(psi) {
    "Annotations backed by source files are expected to have PSI available"
}