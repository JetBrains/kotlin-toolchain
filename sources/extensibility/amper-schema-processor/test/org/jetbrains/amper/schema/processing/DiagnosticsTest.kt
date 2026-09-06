/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

@Tag("gold-file")
class DiagnosticsTest : SchemaProcessorTestBase() {

    @Test
    fun `schema smoke`() = runSchemaTest {
        givenPluginSettingsClassName("com.example.MySettings")
        givenSourceFile(
            $$"""
typealias MyBoolean = Boolean?
typealias ListOfString = List<String>
typealias MapToListOfPath = Map<String, ListOfString>
typealias ListsSchema = Lists

enum class MyKind {
    Kind1,
    Kind2,
    Kind3,
}

interface NoSchema { val foo: Boolean }

@Configurable /*{{*/class/*}} `@Configurable` declaration must be an interface */ NoSchema2 { val foo: Boolean }
@Configurable enum /*{{*/class/*}} `@Configurable` declaration must be an interface */ NoSchemaEnum { FOO }

@Configurable interface Empty

@Configurable interface Lists {                 
  val enabled: Boolean
  val data: List<String>
  val data2: List<Lists>
  val aliasList: ListOfString
  val malformedType1: /*{{*/List/*}} Unexpected schema type `List`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
  val malformedType2: /*{{*/List<Int, String>/*}} Unexpected schema type `List`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
}

@Configurable interface Maps {
   val mapProperty1: Map<String, String>
   val mapProperty2: Map</*{{*/Int/*}} Only `String` is allowed as a `Map` key type in `@Configurable` interfaces */, String>
   val mapProperty3: Map<String, /*{{*/NoSchema/*}} Unexpected schema type `com.example.NoSchema`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */>
   val mapProperty4: Map<String, Lists>
   val mapProperty5: Map<String, Maps>
   val aliasMap: MapToListOfPath
   
   val malformedType3: /*{{*/Map/*}} Unexpected schema type `Map`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
   val malformedType4: /*{{*/Map/*}} Unexpected schema type `Map`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
}

@Configurable interface Nullable {
  val enum: MyKind?
  val boolean: Boolean?
  val string: String?
  val int: Int?
  val path: Path?
  val booleanAlias: MyBoolean
  val listsOptional: ListsSchema?
}

@Configurable interface WithValidDefaults {
  val boolean0: Boolean get() = false && true
  val boolean1: Boolean get() = DEFAULT_TRUE
  val boolean3 get() = true
  val string1 get() = "hello"
  val string2 get() = "hello $CONST_STR"
  val string3: String? get() = "hello" + "World"
  val string4: String? get() = /*{{*/null/*}} No need to specify `null` explicitly: nullable properties are null by default */
  val int: Int get() = 1 + 2
  val int2: Int get() = Int.MAX_VALUE
  val listOfString get() = listOf("a", "b", "c")
  val listOfString2: List<String> get() = emptyList()
  val listOfString1: List<String?> get() = listOf(null, "hello" + "foo", CONST_STR)
  val listOfMaps: List<Map<String, String>> get() = listOf(emptyMap(), emptyMap())
  val mapOfList: Map<String, List<String>> get() = emptyMap()
  val map: Map<String, Int> get() = emptyMap()
  val obj1: Maps? get() = /*{{*/null/*}} No need to specify `null` explicitly: nullable properties are null by default */
  val path: Path? get() = /*{{*/null/*}} No need to specify `null` explicitly: nullable properties are null by default */
  val enum: MyKind? get() = /*{{*/null/*}} No need to specify `null` explicitly: nullable properties are null by default */
 
  companion object {
     const val DEFAULT_TRUE = true
     const val CONST_STR = "World!"
  }
}

@Configurable interface WithInvalidDefaults {
  /*{{*/val something get() = null/*}} Unexpected schema type `kotlin.Nothing?`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
  /*{{*/val listOfSomething get() = listOf(null)/*}} Unexpected schema type `kotlin.Nothing?`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */

  val boolean1: Boolean get() = /*{{*/System.getProperty("hello") != null/*}} Invalid primitive default expression: only simple constant expressions are allowed */                  
  // TODO: Support this case in some capacity?
  val boolean2: Boolean get() = /*{{*/boolean1/*}} Invalid primitive default expression: only simple constant expressions are allowed */
  val int1: Int get() = /*{{*/0 / 0/*}} Invalid primitive default expression: only simple constant expressions are allowed */
  val int2: Int? get() = /*{{*/System.getProperty("hello")?.length/*}} Invalid primitive default expression: only simple constant expressions are allowed */
  // TODO: Support this case?
  val path: Path get() = /*{{*/kotlin.io.path.Path("foo/bar")/*}} Defaults for `Path`s are not yet supported */
  val list1: List<String> get() = /*{{*/arrayOf("hello", "foo").toList()/*}} Invalid list default expression: only `emptyList()` or `listOf(...)` calls are allowed */
  val list2: List<List<String>> get() = listOf(/*{{*/arrayOf("a").toList()/*}} Invalid list default expression: only `emptyList()` or `listOf(...)` calls are allowed */)
  // TODO: Support this case
  val map: Map<String, Int> get() = /*{{*/mapOf("a" to 1, "b" to 2, "c" to 3)/*}} Invalid map default expression: only `emptyMap()` call are allowed */
  val obj: WithValidDefaults get() = /*{{*/object : WithValidDefaults {}/*}} Explicit defaults for `@Configurable` interfaces are not supported. Every non-nullable configurable interface is instantiated by default when all its properties are set. */
  val obj1 get() = /*{{*/object : WithValidDefaults {}/*}} Explicit defaults for `@Configurable` interfaces are not supported. Every non-nullable configurable interface is instantiated by default when all its properties are set. */

  val enum: MyKind get() = MyKind.Kind1

  val withBlockBody: Int 
    /*{{*/get() { return 0 }/*}} Default property getter must have an expression body */
}

@Configurable interface Invalid /*{{*/<T>/*}} Generics are not allowed in `@Configurable` interfaces */ : /*{{*/Empty/*}} Supertypes for user-defined `@Configurable` interfaces are reserved for future use */ {
   val foo: Boolean
   
   val /*{{*/<T>/*}} Generics are not allowed in `@Configurable` interfaces */ /*{{*/T/*}} Extension properties are not allowed in `@Configurable` interfaces */.withReceiver: Int
   val /*{{*/Int/*}} Extension properties are not allowed in `@Configurable` interfaces */.withReceiver2: String get() = ""

   /*{{*/context(_: String)/*}} Context parameters are not allowed in `@Configurable` interfaces */
   val withContext: Int
   
   /*{{*/var/*}} Mutable properties are not allowed in `@Configurable` interfaces */ mutable: String
   
   /*{{*/fun forbidden() {
      exitProcess(0)
   }/*}} Functions are not allowed in `@Configurable` interfaces */
   class Unrelated {
       fun allowed() {}
   }
}

// Main schema
@Configurable interface MySettings {
   val /*{{*/enabled/*}} `enabled` property name is reserved in the plugin's settings (`com.example.MySettings` is specified as the `settingsClass` in `module.yaml`) */: String
   
   val booleanProperty: Boolean
   val stringProperty: String
   val intProperty: Int
   val pathProperty: Path
   val floatProperty: /*{{*/Float/*}} Unexpected schema type `kotlin.Float`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
   val unresolved: /*{{*/SomeOtherType/*}} Unexpected schema type `SomeOtherType`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
   val functionType: /*{{*/() -> Unit/*}} Unexpected schema type `() -> kotlin.Unit`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
   
   val nonSchema: /*{{*/NoSchema/*}} Unexpected schema type `com.example.NoSchema`.
Supported types are:
 - `Boolean`, `String`, `Int`, `Path`, enums
 - `@Configurable` interface (must be declared in the same source directory)
 - `List<T>`, `Map<String, T>`, where `T` is a supported type. */
   val empty: Empty
   val lists: ListsSchema
   val maps: Maps
}

@Configurable
/*{{*/internal/*}} `@Configurable` interface must be public */
interface Config {
  val builtinTypeReference1: Dependency
  val builtinTypeReference2: Dependency.Local
  val builtinTypeReference3: Classpath
  val map: Map<String, List<Path>>
}

object Hello {
    /*{{*/@[JvmStatic TaskAction]
    fun nested()/*}} `@TaskAction` function must be a top-level function */
}

@TaskAction fun /*{{*/overloaded/*}} Illegal overload for `com.example.overloaded`: `@TaskAction` functions can't be overloaded */() {}
@TaskAction
/*{{*/private/*}} `@TaskAction` function must be public */
fun /*{{*/overloaded/*}} Illegal overload for `com.example.overloaded`: `@TaskAction` functions can't be overloaded */(int: Int) {}

@TaskAction
/*{{*/context(_: String)/*}} Context parameters are not allowed in a `@TaskAction` function */
/*{{*/suspend/*}} Suspending `@TaskAction` functions are not yet supported */ /*{{*/inline/*}} `@TaskAction` function can't be marked as inline */ fun /*{{*/<T>/*}} `@TaskAction` function can't be generic */ /*{{*/T/*}} `@TaskAction` function can't be an extension function */.invalidTaskAction(
  int: Int = 0,
  map: Map<String, String> = emptyMap(),
  /*{{*/list: List<Path> = emptyList()/*}} Parameter of a `Path`-referencing type must be annotated with either `@Input` or `@Output` */,
  @Input inputDir: Path? = /*{{*/null/*}} No need to specify `null` explicitly: nullable properties are null by default */,
  @Output outputDir: Path,
  /*{{*/somePath: Path/*}} Parameter of a `Path`-referencing type must be annotated with either `@Input` or `@Output` */,
  /*{{*/@Input @Output anotherPath: Path/*}} Both `@Input` and `@Output` annotations can't be specified for a single parameter. File updates in-place are not supported. Use separate input/output instead. */,
  /*{{*/@[Input Output] crazyPath: Path/*}} Both `@Input` and `@Output` annotations can't be specified for a single parameter. File updates in-place are not supported. Use separate input/output instead. */,
  /*{{*/config: Config/*}} Parameter of a `Path`-referencing type must be annotated with either `@Input` or `@Output` */,
  @Input inputConfig: Config,
  @Output outputList: Map<String, Path>,
): /*{{*/String/*}} `@TaskAction` function must return Unit */ {
  error("woof!")
}
"""
        )
        
        expectPluginData(Path("testResources/schema-smoke.json"))
    }

    @Test
    fun `enum constant names`() = runSchemaTest {
        givenSourceFile("""
@Configurable interface Settings { val prop: MyEnum }
enum class MyEnum {
    MY_CONSTANT,
    MyConstant,
    `hello-3world`,
    @EnumValue("yaml-name")
    MY_CONSTANT2,
}
        """.trimIndent())

        expectPluginData(Path("testResources/enum-constant-names.json"))
    }

    @Test
    fun `cyclic references`() = runSchemaTest {
        givenSourceFile("""

@Configurable
interface InfiniteLinkedList {
  val value: String
  val /*{{*/next/*}} Type(s) `com.example.InfiniteLinkedList` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: InfiniteLinkedList
}

@Configurable
interface FiniteLinkedList {
  val value: String
  val next: FiniteLinkedList?
}

@Configurable
interface PartOfTheLoop1 {
  val /*{{*//*{{*/foo/*}} Type(s) `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2`, `com.example.PartOfTheLoop3` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). *//*}} Type(s) `com.example.PartOfTheLoop3A`, `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: PartOfTheLoop2
}

@Configurable
interface PartOfTheLoop2 {
  val /*{{*/foo/*}} Type(s) `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2`, `com.example.PartOfTheLoop3` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: PartOfTheLoop3
  val /*{{*/bar/*}} Type(s) `com.example.PartOfTheLoop3A`, `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: PartOfTheLoop3A
  val quu: NotPartOfTheLoop
}

@Configurable
interface PartOfTheLoop3 {
  val /*{{*/foo/*}} Type(s) `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2`, `com.example.PartOfTheLoop3` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: PartOfTheLoop1
}

@Configurable
interface PartOfTheLoop3A {
  val /*{{*/foo/*}} Type(s) `com.example.PartOfTheLoop3A`, `com.example.PartOfTheLoop1`, `com.example.PartOfTheLoop2` form a self-referential cycle. This makes the type(s) impossible to construct. Please rework your configuration or make a property from the cycle optional (nullable). */: PartOfTheLoop1
}

@Configurable
interface NotPartOfTheLoop {
  val foo: PartOfTheLoop1?
}

        """.trimIndent())
        expectPluginData(Path("testResources/cyclic-references.json"))
    }
}
