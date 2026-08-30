/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.generated.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.pathString

class TypeCheckTest {
    private val stringType = SchemaType.StringType()
    private val mainClassStringType = SchemaType.StringType(semantics = SchemaType.StringType.Semantics.JvmMainClass, canBeBlank = false)
    private val nullableStringType = SchemaType.StringType(isMarkedNullable = true)
    private val nonBlankStringType = SchemaType.StringType(canBeBlank = false)
    private val intType = SchemaType.IntType()
    private val booleanType = SchemaType.BooleanType()
    private val pathType = SchemaType.PathType()

    private val allWellDefinedTypes = listOf(
        stringType,
        intType,
        booleanType,
        pathType,
        SchemaType.EnumType(DeclarationOfEnumAllOpenPreset),
        SchemaType.ListType(stringType),
        SchemaType.MapType(stringType),
        SchemaType.ObjectType(DeclarationOfSettings),
        SchemaType.VariantType(DeclarationOfVariantDependency),
    )

    @Test
    fun `isAssignableFrom - same simple type`() {
        assertTrue(stringType.isAssignableFrom(stringType))
        assertTrue(intType.isAssignableFrom(intType))
        assertTrue(booleanType.isAssignableFrom(booleanType))
        assertTrue(pathType.isAssignableFrom(pathType))
    }

    @Test
    fun `isAssignableFrom - different types`() {
        // Path, Enum, and Int are assignable to String with conversion
        assertTrue(stringType.isAssignableFrom(pathType))
        assertTrue(stringType.isAssignableFrom(intType))
        
        // Boolean is NOT assignable to String
        assertFalse(stringType.isAssignableFrom(booleanType))

        // String is NOT assignable to Int or Boolean
        assertFalse(intType.isAssignableFrom(stringType))
        assertFalse(booleanType.isAssignableFrom(stringType))
        
        // String is NOT assignable to Path
        assertFalse(pathType.isAssignableFrom(stringType))

        // But NOT when conversion is disabled
        assertFalse(pathType.isAssignableFrom(stringType, allowStringConversion = false))
        assertFalse(stringType.isAssignableFrom(pathType, allowStringConversion = false))
        assertFalse(stringType.isAssignableFrom(intType, allowStringConversion = false))
    }

    @Test
    fun `isAssignableFrom - nullability`() {
        assertTrue(nullableStringType.isAssignableFrom(stringType))
        assertTrue(nullableStringType.isAssignableFrom(nullableStringType))
        assertFalse(stringType.isAssignableFrom(nullableStringType))
        assertTrue(stringType.isAssignableFrom(stringType))
    }

    @Test
    fun `isAssignableFrom - conversion to string`() {
        assertTrue(stringType.isAssignableFrom(pathType))
        assertFalse(stringType.isAssignableFrom(pathType, allowStringConversion = false))

        val enumType = SchemaType.EnumType(DeclarationOfEnumAllOpenPreset)
        assertTrue(stringType.isAssignableFrom(enumType))
        assertFalse(stringType.isAssignableFrom(enumType, allowStringConversion = false))

        assertTrue(stringType.isAssignableFrom(intType))
        assertFalse(stringType.isAssignableFrom(intType, allowStringConversion = false))
        
        // String with semantics should NOT do conversions
        assertFalse(mainClassStringType.isAssignableFrom(pathType))
        assertFalse(mainClassStringType.isAssignableFrom(enumType))
        assertFalse(mainClassStringType.isAssignableFrom(intType))
    }

    @Test
    fun `isAssignableFrom - non-blank string types`() {
        assertTrue(nonBlankStringType.isAssignableFrom(nonBlankStringType))
        assertFalse(nonBlankStringType.isAssignableFrom(stringType))
        assertTrue(stringType.isAssignableFrom(nonBlankStringType))

        val nullableNonBlankStringType = nonBlankStringType.copy(isMarkedNullable = true)
        assertTrue(nullableNonBlankStringType.isAssignableFrom(nonBlankStringType))
        assertFalse(nonBlankStringType.isAssignableFrom(nullableNonBlankStringType))
    }

    @Test
    fun `isAssignableFrom - conversions to non-blank string`() {
        // Paths, enums, and integers always convert to non-blank strings
        assertTrue(nonBlankStringType.isAssignableFrom(pathType))
        assertTrue(nonBlankStringType.isAssignableFrom(intType))
        assertTrue(nonBlankStringType.isAssignableFrom(SchemaType.EnumType(DeclarationOfEnumAllOpenPreset)))
    }

    @Test
    fun `isAssignableFrom - list types`() {
        val stringList = SchemaType.ListType(stringType)
        val nullableStringList = SchemaType.ListType(nullableStringType)
        val intList = SchemaType.ListType(intType)
        
        assertTrue(stringList.isAssignableFrom(stringList))
        assertFalse(stringList.isAssignableFrom(intList))

        // Lists do not allow converting elements
        val pathList = SchemaType.ListType(pathType)
        assertFalse(pathList.isAssignableFrom(stringList))
        assertFalse(stringList.isAssignableFrom(pathList))

        // List[string?] is assignable from List[string]
        assertTrue(nullableStringList.isAssignableFrom(stringList))
    }

    @Test
    fun `isAssignableFrom - map types`() {
        val stringMap = SchemaType.MapType(stringType)
        val intMap = SchemaType.MapType(intType)
        
        assertTrue(stringMap.isAssignableFrom(stringMap))
        assertFalse(stringMap.isAssignableFrom(intMap))
        
        // Maps do not allow converting keys/values
        val pathMap = SchemaType.MapType(pathType)
        assertFalse(pathMap.isAssignableFrom(stringMap))
        assertFalse(stringMap.isAssignableFrom(pathMap))

        // Different key types
        val customKeyMap = SchemaType.MapType(stringType, keyType = mainClassStringType)
        
        // Maps are assignable if their key types are assignable.
        assertTrue(stringMap.isAssignableFrom(customKeyMap))
        
        // But the other way around it should fail because customKeyMap HAS semantics
        assertFalse(customKeyMap.isAssignableFrom(stringMap))
    }

    @Test
    fun `isAssignableFrom - enum types`() {
        val enumType1 = SchemaType.EnumType(DeclarationOfEnumAllOpenPreset)
        val enumType2 = SchemaType.EnumType(DeclarationOfEnumAmperLayout)

        assertTrue(enumType1.isAssignableFrom(enumType1))
        
        // Enum to String conversion
        assertTrue(stringType.isAssignableFrom(enumType1))
        assertFalse(stringType.isAssignableFrom(enumType1, allowStringConversion = false))

        // Two unrelated enums are not assignable
        assertFalse(enumType1.isAssignableFrom(enumType2))
        assertFalse(enumType2.isAssignableFrom(enumType1))
    }

    @Test
    fun `isAssignableFrom - object types`() {
        val objType1 = SchemaType.ObjectType(DeclarationOfSettings)
        
        assertTrue(objType1.isAssignableFrom(objType1))

        val objType2 = SchemaType.ObjectType(DeclarationOfCatalogDependency)
        assertFalse(objType1.isAssignableFrom(objType2))
    }

    @Test
    fun `isAssignableFrom - variant types`() {
        val variantType = SchemaType.VariantType(DeclarationOfVariantDependency)
        val leafType1 = SchemaType.ObjectType(DeclarationOfCatalogDependency)
        
        assertTrue(variantType.isAssignableFrom(variantType))
        assertTrue(variantType.isAssignableFrom(leafType1))
    }

    @Test
    fun `cast - simple types`() {
        val stringNode = StringNode("hello", null, DefaultTrace, EmptyContexts)
        assertSame(stringNode, stringType.cast(stringNode))
        
        val intNode = IntNode(42, DefaultTrace, EmptyContexts)
        assertSame(intNode, intType.cast(intNode))
        
        val booleanNode = BooleanNode(true, DefaultTrace, EmptyContexts)
        assertSame(booleanNode, booleanType.cast(booleanNode))
    }

    @Test
    fun `cast - conversion path to string`() {
        val path = Path("/some/path")
        val pathNode = PathNode(path, DefaultTrace, EmptyContexts)
        val casted = stringType.cast(pathNode)

        assertInstanceOf<StringNode>(casted)
        assertEquals(path.pathString, casted.value)

        assertNull(stringType.cast(pathNode, allowStringConversion = false))
        assertNull(mainClassStringType.cast(pathNode))
    }

    @Test
    fun `cast - conversion empty path to string`() {
        val emptyPathNode = PathNode(Path(""), DefaultTrace, EmptyContexts)

        assertEquals("", assertInstanceOf<StringNode>(stringType.cast(emptyPathNode)).value)

        // An empty path denotes the current directory, and the converted string must stay non-blank
        assertEquals(".", assertInstanceOf<StringNode>(nonBlankStringType.cast(emptyPathNode)).value)
    }

    @ParameterizedTest
    // Actual spaces or tabs are not allowed, but some other special whitespace chars are
    @ValueSource(strings = [
        "\u00A0", // SPACE SEPARATOR
        "\u2007", // LINE SEPARATOR
        "\u202F", // PARAGRAPH SEPARATOR
    ])
    fun `cast - conversion blank path to string`(blankStr: String) {
        val blankPathNode = PathNode(Path(blankStr), DefaultTrace, EmptyContexts)

        assertEquals(blankStr, assertInstanceOf<StringNode>(stringType.cast(blankPathNode)).value)
        assertEquals(".${File.separatorChar}$blankStr", assertInstanceOf<StringNode>(nonBlankStringType.cast(blankPathNode)).value)
    }

    @Test
    fun `cast - conversion enum to string`() {
        val enumNode = EnumNode("Spring", DeclarationOfEnumAllOpenPreset, DefaultTrace, EmptyContexts)
        
        val casted = stringType.cast(enumNode)
        assertInstanceOf<StringNode>(casted)
        assertEquals("spring", casted.value)

        assertNull(stringType.cast(enumNode, allowStringConversion = false))
        assertNull(mainClassStringType.cast(enumNode))
    }

    @Test
    fun `cast - conversion int to string`() {
        val intNode = IntNode(123, DefaultTrace, EmptyContexts)
        val casted = stringType.cast(intNode)
        
        assertInstanceOf<StringNode>(casted)
        assertEquals("123", casted.value)

        assertNull(stringType.cast(intNode, allowStringConversion = false))
        assertNull(mainClassStringType.cast(intNode))
    }

    @Test
    fun `cast - non-blank string type checks the actual value`() {
        val nonBlankNode = StringNode("hello", null, DefaultTrace, EmptyContexts)
        assertSame(nonBlankNode, nonBlankStringType.cast(nonBlankNode))

        assertNull(nonBlankStringType.cast(StringNode("", null, DefaultTrace, EmptyContexts)))
        assertNull(nonBlankStringType.cast(StringNode(" \t ", null, DefaultTrace, EmptyContexts)))
    }

    @Test
    fun `cast - conversions to non-blank string`() {
        val intNode = IntNode(123, DefaultTrace, EmptyContexts)
        assertEquals("123", assertInstanceOf<StringNode>(nonBlankStringType.cast(intNode)).value)

        val enumNode = EnumNode("Spring", DeclarationOfEnumAllOpenPreset, DefaultTrace, EmptyContexts)
        assertEquals("spring", assertInstanceOf<StringNode>(nonBlankStringType.cast(enumNode)).value)

        val path = Path("/some/path")
        val pathNode = PathNode(path, DefaultTrace, EmptyContexts)
        assertEquals(path.pathString, assertInstanceOf<StringNode>(nonBlankStringType.cast(pathNode)).value)
    }

    @Test
    fun `cast - non-blank string type with nodes that have no value yet`() {
        // Nodes without an actual value can only be checked at the type level
        assertNull(nonBlankStringType.cast(ErrorNode(stringType, DefaultTrace, EmptyContexts)))

        val refNode = ReferenceNode(["foo".ts], stringType, transform = null, DefaultTrace, EmptyContexts)
        assertNull(nonBlankStringType.cast(refNode))

        val errorNode = ErrorNode(nonBlankStringType, DefaultTrace, EmptyContexts)
        assertSame(errorNode, nonBlankStringType.cast(errorNode))

        val nonBlankRefNode = ReferenceNode(["foo".ts], nonBlankStringType, null, DefaultTrace, EmptyContexts)
        assertSame(nonBlankRefNode, nonBlankStringType.cast(nonBlankRefNode))
    }

    @Test
    fun `cast - list types`() {
        val stringListType = SchemaType.ListType(stringType)
        val nullableStringListType = SchemaType.ListType(nullableStringType)
        val listNode = RefinedListNode(
            listOf(
                StringNode("a", null, DefaultTrace, EmptyContexts),
            ), DefaultTrace, EmptyContexts
        )
        
        assertSame(listNode, stringListType.cast(listNode))
        assertSame(listNode, nullableStringListType.cast(listNode))

        val intListType = SchemaType.ListType(intType)
        val mavenStringListType = SchemaType.ListType(mainClassStringType)
        assertNull(intListType.cast(listNode))
        assertNull(mavenStringListType.cast(listNode))
    }

    @Test
    fun `cast - empty list is assignable to any type`() {
        val stringMapType = SchemaType.ListType(stringType)
        val variantMapType = SchemaType.ListType(DeclarationOfVariantDependency.toType())
        val intMapType = SchemaType.ListType(intType)

        val emptyList = RefinedListNode(emptyList(), DefaultTrace, EmptyContexts)

        // Empty map is assignable to any map type
        assertSame(emptyList, stringMapType.cast(emptyList))
        assertSame(emptyList, variantMapType.cast(emptyList))
        assertSame(emptyList, intMapType.cast(emptyList))
    }

    @Test
    fun `cast - map types`() {
        val stringMapType = SchemaType.MapType(stringType)
        val nullableStringMapType = SchemaType.MapType(nullableStringType)

        // Map with a string value
        val mapNode = RefinedMappingNode(
            mapOf(
                "key" to RefinedKeyValue(
                    "key", DefaultTrace, StringNode("hello", null, DefaultTrace, EmptyContexts), DefaultTrace,
                )
            ), declaration = null, DefaultTrace, EmptyContexts
        )

        // Compatible
        assertSame(mapNode, stringMapType.cast(mapNode))
        assertSame(mapNode, nullableStringMapType.cast(mapNode))

        // Incompatible
        val mavenStringMapType = SchemaType.MapType(mainClassStringType)
        val intMapType = SchemaType.MapType(intType)
        assertNull(intMapType.cast(mapNode))
        assertNull(mavenStringMapType.cast(mapNode))
    }

    @Test
    fun `cast - empty map is assignable to any type`() {
        val stringMapType = SchemaType.MapType(stringType)
        val variantMapType = SchemaType.MapType(DeclarationOfVariantDependency.toType())
        val intMapType = SchemaType.MapType(intType)

        val emptyMap = RefinedMappingNode(emptyMap(), declaration = null, DefaultTrace, EmptyContexts)

        // Empty map is assignable to any map type
        assertSame(emptyMap, stringMapType.cast(emptyMap))
        assertSame(emptyMap, variantMapType.cast(emptyMap))
        assertSame(emptyMap, intMapType.cast(emptyMap))
    }

    @Test
    fun `cast - object types`() {
        val objDeclaration1 = DeclarationOfSettings
        val objNode1 = RefinedMappingNode(emptyMap(), objDeclaration1, DefaultTrace, EmptyContexts)

        assertSame(objNode1, objDeclaration1.toType().cast(objNode1))

        val objType2 = SchemaType.ObjectType(DeclarationOfCatalogDependency)
        assertNull(objType2.cast(objNode1))
    }

    @Test
    fun `cast - variant types`() {
        val variantType = SchemaType.VariantType(DeclarationOfVariantDependency)
        
        // Leaf type that belongs to the variant
        val leafNode = RefinedMappingNode(emptyMap(), DeclarationOfCatalogDependency, DefaultTrace, EmptyContexts)
        
        assertSame(leafNode, variantType.cast(leafNode))

        // Leaf type that does NOT belong to the variant
        val otherNode = RefinedMappingNode(emptyMap(), DeclarationOfSettings, DefaultTrace, EmptyContexts)
        
        assertNull(variantType.cast(otherNode))
    }

    @Test
    fun `cast - list types do not convert elements`() {
        val stringListType = SchemaType.ListType(stringType)
        val enumDecl = DeclarationOfEnumAllOpenPreset
        val enumNode = EnumNode("Spring", enumDecl, DefaultTrace, EmptyContexts)
        
        val enumListNode = RefinedListNode(listOf(enumNode), DefaultTrace, EmptyContexts)

        assertNull(stringListType.cast(enumListNode))
    }

    @Test
    fun `cast - map types do not convert elements`() {
        val stringMapType = SchemaType.MapType(stringType)
        val enumNode = EnumNode("Spring", DeclarationOfEnumAllOpenPreset, DefaultTrace, EmptyContexts)
        
        val kv = RefinedKeyValue("foo", DefaultTrace, enumNode, DefaultTrace)
        val enumMapNode = RefinedMappingNode(mapOf("foo" to kv), declaration = null, DefaultTrace, EmptyContexts)

        assertNull(stringMapType.cast(enumMapNode))
    }

    @Test
    fun `cast - null literal`() {
        val nullNode = NullLiteralNode(DefaultTrace, EmptyContexts)
        
        assertNull(stringType.cast(nullNode))
        assertSame(nullNode, nullableStringType.cast(nullNode))
    }

    @Test
    fun `cast - error node`() {
        val errorNode = ErrorNode(stringType, DefaultTrace, EmptyContexts)
        
        assertSame(errorNode, stringType.cast(errorNode))
        assertNull(intType.cast(errorNode))
        
        // "Covariance" in error node
        val pathErrorNode = ErrorNode(pathType, DefaultTrace, EmptyContexts)
        assertSame(pathErrorNode, stringType.cast(pathErrorNode))
    }

    @Test
    fun `cast - resolvable node`() {
        val refNode = ReferenceNode(listOf("foo".ts), stringType, null, DefaultTrace, EmptyContexts)
        
        assertSame(refNode, stringType.cast(refNode))
        assertNull(intType.cast(refNode))
        
        // "Covariance" in resolvable node
        val pathRefNode = ReferenceNode(listOf("foo".ts), pathType, null, DefaultTrace, EmptyContexts)
        assertSame(pathRefNode, stringType.cast(pathRefNode))
    }

    @Test
    fun `isAssignableFrom - undefined type accepts all types`() {
        val undefinedType = SchemaType.UndefinedType

        // UndefinedType accepts all types
        allWellDefinedTypes.forEach { assertTrue(undefinedType.isAssignableFrom(it)) }
        assertTrue(undefinedType.isAssignableFrom(undefinedType))

        // UndefinedType is NOT assignable to other types (except itself)
        allWellDefinedTypes.forEach { assertFalse(it.isAssignableFrom(undefinedType)) }
    }

    @Test
    fun `cast - undefined type accepts all nodes`() {
        val stringNode = StringNode("hello", null, DefaultTrace, EmptyContexts)
        assertSame(stringNode, SchemaType.UndefinedType.cast(stringNode))

        val intNode = IntNode(42, DefaultTrace, EmptyContexts)
        assertSame(intNode, SchemaType.UndefinedType.cast(intNode))

        val booleanNode = BooleanNode(true, DefaultTrace, EmptyContexts)
        assertSame(booleanNode, SchemaType.UndefinedType.cast(booleanNode))

        val pathNode = PathNode(Path("/some/path"), DefaultTrace, EmptyContexts)
        assertSame(pathNode, SchemaType.UndefinedType.cast(pathNode))

        val enumNode = EnumNode("Spring", DeclarationOfEnumAllOpenPreset, DefaultTrace, EmptyContexts)
        assertSame(enumNode, SchemaType.UndefinedType.cast(enumNode))

        val listNode = RefinedListNode(listOf(stringNode), DefaultTrace, EmptyContexts)
        assertSame(listNode, SchemaType.UndefinedType.cast(listNode))

        val mapNode = RefinedMappingNode(emptyMap(), declaration = null, DefaultTrace, EmptyContexts)
        assertSame(mapNode, SchemaType.UndefinedType.cast(mapNode))

        val objectNode = RefinedMappingNode(emptyMap(), DeclarationOfSettings, DefaultTrace, EmptyContexts)
        assertSame(objectNode, SchemaType.UndefinedType.cast(objectNode))

        val nullNode = NullLiteralNode(DefaultTrace, EmptyContexts)
        assertSame(nullNode, SchemaType.UndefinedType.cast(nullNode))

        val errorNode = ErrorNode(stringType, DefaultTrace, EmptyContexts)
        assertSame(errorNode, SchemaType.UndefinedType.cast(errorNode))

        val refNode = ReferenceNode(listOf("foo".ts), stringType, null, DefaultTrace, EmptyContexts)
        assertSame(refNode, SchemaType.UndefinedType.cast(refNode))

        // UndefinedType nodes cannot be cast to other types
        val undefinedNode = ReferenceNode(listOf("foo".ts), SchemaType.UndefinedType, null, DefaultTrace, EmptyContexts)
        allWellDefinedTypes.forEach { assertNull(it.cast(undefinedNode)) }
    }

    private val String.ts get() = TraceableString(this, DefaultTrace)
}
