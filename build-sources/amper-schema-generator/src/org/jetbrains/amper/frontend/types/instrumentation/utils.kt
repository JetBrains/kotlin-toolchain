/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.frontend.types.instrumentation

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asTypeName
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.SchemaValueDelegate
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.types.SchemaValueWrappingInfo
import java.io.File
import java.nio.file.Path
import kotlin.contracts.contract
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

internal fun FileSpec.Builder.addGeneratedComment() = addFileComment(
        """
        ------------------ GENERATED ---------------------
        This file is produced by `amper-schema-generator`.
        --------------------------------------------------
        """.trimIndent()
    )

internal fun FileSpec.Builder.addSuppressRedundantVisibilityModifier() = addAnnotation(
    AnnotationSpec.builder(Suppress::class)
        .addMember("%S", "REDUNDANT_VISIBILITY_MODIFIER")
        .build()
)

/**
 * Generates a code block that accesses the declaration of the given [declaration] declaration.
 *
 * If the declaration is [parameterized][ParsedDeclaration.SchemaNode.declarationParameters], then all the arguments
 * must be available in the surrounding scope.
 */
internal fun declarationAccessExpression(declaration: ParsedDeclaration) = CodeBlock.builder().apply {
    add("%T", declaration.declarationName)
    if (declaration is ParsedDeclaration.SchemaNode && declaration.declarationParameters.isNotEmpty()) {
        add("(")
        declaration.declarationParameters.forEach {
            add("%N,", it.parameterName)
        }
        add(")")
    }
}.build()

/**
 * Generates a creating expression for the [org.jetbrains.amper.frontend.api.Default.Static.value].
 */
internal fun defaultToCode(default: Any?): CodeBlock = when (default) {
    null -> CodeBlock.of("null")
    is Boolean, is Int -> CodeBlock.of("%L", default)
    is String -> CodeBlock.of("%S", default)
    is Path, is File -> CodeBlock.of("%M(%S)", PathConstructor, default.toString())
    is TraceableValue<*> -> CodeBlock.of("%T(%L, %T)", TraceableValue::class, defaultToCode(default.value), DefaultTrace::class).also {
        check(default.trace === DefaultTrace) { "The default should have a default trace" }
    }
    is Enum<*> -> CodeBlock.of("%T.%N", default::class, default.name)
    is List<*> -> CodeBlock.builder().apply {
        if (default.isEmpty()) {
            add("emptyList<Nothing>()")
        } else {
            add("listOf(")
            default.forEach { add("%L, ", defaultToCode(it)) }
            add(")")
        }
    }.build()
    is Map<*, *> -> CodeBlock.of("emptyMap<Nothing, Nothing>()").also {
        check(default.isEmpty()) { "Only empty map is supported as a default value" }
    }
    else -> {
        val clazz = default::class
        if (clazz.isValue) {
            val value = clazz.memberProperties.single().call(default)
            return CodeBlock.of("%T(%L)", clazz, defaultToCode(value))
        }
        error("Unexpected default value: $default")
    }
}

context(generator: Generator)
internal fun ensureParsed(klass: KClass<*>): ParsedDeclaration = when {
    klass.isSubclassOf<Enum<*>>() -> generator.ensureEnumParsed(klass)
    klass.isSubclassOf<SchemaNode>() -> if (klass.isSealed) {
        generator.ensureSealedSchemaNodeParsed(klass)
    } else {
        generator.ensureSchemaNodeParsed(klass)
    }
    else -> error("Unexpected class $klass. Expected a SchemaNode or an Enum")
}

@Suppress("KotlinConstantConditions")
internal inline fun <reified T : Any> KClass<*>.isSubclassOf(): Boolean {
    contract { returns(true) implies (this@isSubclassOf is KClass<out T>) }
    return isSubclassOf(T::class)
}

internal fun KClass<out SchemaNode>.deepSealedSubclasses(): List<KClass<out SchemaNode>> =
    if (isSealed) sealedSubclasses.fold(emptyList()) { a, c -> a + c.deepSealedSubclasses() } else listOf(this)

internal fun <T : SchemaNode> KProperty1<T, *>.schemaDelegate(receiver: T): SchemaValueDelegate<*> =
    apply { isAccessible = true }.getDelegate(receiver)?.let {
        it as SchemaValueDelegate<*>
    } ?: error("Property $this should have a schema delegate")

internal fun WrappingInfoDescriptor.copy(
    isTraceableWrapped: Boolean = this.isTraceableWrapped,
    valueClassWrapper: ValueClassWrapperInfo? = this.valueClassWrapper,
) = when (this) {
    is WrappingInfoDescriptor.List -> copy(
        isTraceableWrapped = isTraceableWrapped,
        valueClassWrapper = valueClassWrapper,
    )
    is WrappingInfoDescriptor.Map -> copy(
        isTraceableWrapped = isTraceableWrapped,
        valueClassWrapper = valueClassWrapper,
    )
    is WrappingInfoDescriptor.Plain -> copy(
        isTraceableWrapped = isTraceableWrapped,
        valueClassWrapper = valueClassWrapper,
    )
}

private fun WrappingInfoDescriptor.toWrapValueCodeBlock(): CodeBlock = if (isTraceableWrapped) {
    valueClassWrapper?.let {
        CodeBlock.of("{ v, t -> v?.let { %T(it as %T) }?.%M(t) }", it.wrapperClass, it.valueType.asTypeName(), AsTraceableMemberName)
    } ?: CodeBlock.of("Any?::%M", AsTraceableMemberName)
} else {
    valueClassWrapper?.let {
        CodeBlock.of("{ v, _ -> v?.let { %T(it as %T) } }", it.wrapperClass, it.valueType.asTypeName())
    } ?: CodeBlock.of("null")
}

private fun WrappingInfoDescriptor.toUnwrapValueCodeBlock(): CodeBlock = if (isTraceableWrapped) {
    valueClassWrapper?.let {
        CodeBlock.of(
            "{ v -> (v as %T<*>).value?.let { (it as %T).%N } }",
            TraceableValue::class,
            it.wrapperClass,
            it.valuePropertyName,
        )
    } ?: CodeBlock.of("{ v -> (v as %T<*>).value }", TraceableValue::class)
} else {
    valueClassWrapper?.let {
        CodeBlock.of("{ v -> v?.let { (it as %T).%N } }", it.wrapperClass, it.valuePropertyName)
    } ?: CodeBlock.of("null")
}

internal fun WrappingInfoDescriptor?.toCodeBlock(): CodeBlock = when (this) {
    null -> CodeBlock.of("null")
    is WrappingInfoDescriptor.List -> CodeBlock.of(
        "%T(elementInfo = %L, wrapValue = %L, unwrapValue = %L)",
        SchemaValueWrappingInfo.List::class,
        elementInfo.toCodeBlock(),
        toWrapValueCodeBlock(),
        toUnwrapValueCodeBlock(),
    )
    is WrappingInfoDescriptor.Map -> CodeBlock.of(
        "%T(keyInfo = %L, valueInfo = %L, wrapValue = %L, unwrapValue = %L)",
        SchemaValueWrappingInfo.Map::class,
        keyInfo.toCodeBlock(),
        valueInfo.toCodeBlock(),
        toWrapValueCodeBlock(),
        toUnwrapValueCodeBlock(),
    )
    is WrappingInfoDescriptor.Plain -> CodeBlock.of(
        "%T(wrapValue = %L, unwrapValue = %L)",
        SchemaValueWrappingInfo.Plain::class,
        toWrapValueCodeBlock(),
        toUnwrapValueCodeBlock(),
    )
}

private val AsTraceableMemberName = MemberName("org.jetbrains.amper.frontend.api", "asTraceable")
