/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.jetbrains.amper.plugins.schema.model.Defaults
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.diagnostics.KotlinSchemaBuildProblem
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.psi.KtExpression

context(session: KaSession, _: DiagnosticsReporter)
internal fun parseDefaultExpression(
    expression: KtExpression,
    type: PluginData.Type,
    nestedExpression: Boolean = false,
): Defaults? {
    val constant by lazy { with(session) { expression.evaluate() } }

    val call by lazy {
        with(session) {
            when (val info = expression.resolveToCall()) {
                is KaSuccessCallInfo -> info.call
                /*
                 If there is a single candidate call, we roll with that too.
                 This covers cases where the `listOf(foo)` call is unable to deduce the return type
                  due to `foo` being some unresolved expression.
                 We allow the algo to go one step further and actually reach `foo` and report it specifically,
                  rather than just complaining about the whole `listOf(foo)` call.
                */
                is KaErrorCallInfo -> info.candidateCalls.singleOrNull()
                null -> null
            }
        }
    }

    if (type.isNullable && constant is KaConstantValue.NullValue) {
        if (!nestedExpression) {
            report(expression, KotlinSchemaBuildProblem::DefaultsRedundantNull)
        }
        return Defaults.Null
    }
    return when (type) {
        is PluginData.Type.BooleanType -> (constant as? KaConstantValue.BooleanValue)
            ?.let { Defaults.BooleanDefault(it.value) }
            ?: run { report(expression, KotlinSchemaBuildProblem::DefaultsInvalidConstant); null }
        is PluginData.Type.IntType -> (constant as? KaConstantValue.IntValue)
            ?.let { Defaults.IntDefault(it.value) }
            ?: run { report(expression, KotlinSchemaBuildProblem::DefaultsInvalidConstant); null }
        is PluginData.Type.StringType -> (constant as? KaConstantValue.StringValue)
            ?.let { Defaults.StringDefault(it.value) }
            ?: run { report(expression, KotlinSchemaBuildProblem::DefaultsInvalidConstant); null }
        is PluginData.Type.EnumType -> when (val symbol = (call as? KaVariableAccessCall)?.symbol) {
            is KaEnumEntrySymbol -> Defaults.EnumDefault(symbol.name.identifier)
            else -> {
                report(expression, KotlinSchemaBuildProblem::DefaultsInvalidEnum); null
            }
        }
        is PluginData.Type.ListType -> (call as? KaFunctionCall<*>)?.let { call ->
            when (call.symbol.callableId) {
                EMPTY_LIST -> Defaults.ListDefault(emptyList())
                LIST_OF -> Defaults.ListDefault(call.valueArgumentMapping.mapNotNull { [e] ->
                    parseDefaultExpression(e, type.elementType, nestedExpression = true)
                })
                else -> null
            }
        } ?: run{ report(expression, KotlinSchemaBuildProblem::DefaultsInvalidList); null }
        is PluginData.Type.MapType -> (call as? KaFunctionCall<*>)?.let { call ->
            when (call.symbol.callableId) {
                EMPTY_MAP -> Defaults.MapDefault(emptyMap())
                // TODO: MAP_OF
                else -> null
            }
        } ?: run { report(expression, KotlinSchemaBuildProblem::DefaultsInvalidMap); null }
        is PluginData.Type.ObjectType,
        is PluginData.Type.VariantType -> {
            report(expression, KotlinSchemaBuildProblem::DefaultsInvalidObject); null
        }
        is PluginData.Type.PathType -> {
            report(expression, KotlinSchemaBuildProblem::DefaultsInvalidPath); null
        }
    }
}
