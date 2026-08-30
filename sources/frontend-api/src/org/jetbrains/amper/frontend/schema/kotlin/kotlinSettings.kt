/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.kotlin

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.frontend.EnumMap
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaEnum
import org.jetbrains.amper.frontend.api.CanBeReferenced
import org.jetbrains.amper.frontend.api.DeprecatedSchema
import org.jetbrains.amper.frontend.api.EnumOrderSensitive
import org.jetbrains.amper.frontend.api.EnumValueFilter
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.NotBlank
import org.jetbrains.amper.frontend.api.PlatformAgnostic
import org.jetbrains.amper.frontend.api.PlatformSpecific
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.KspSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.AllOpenSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.DataFrameSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.JsPlainObjectsSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.KotlinxRpcSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.NoArgSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.PowerAssertSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.SerializationSettings
import org.jetbrains.amper.frontend.schema.kotlin.plugins.ThirdPartyCompilerPlugin
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.tree.StringNode

/**
 * The expected pattern for the Kotlin compiler version setting.
 * It's used in diagnostics and to extract the default language version from the compiler version string.
 */
val KotlinCompilerVersionPattern = Regex("""(?<languageVersion>\d+\.\d+)\..*""")

@EnumOrderSensitive(reverse = true)
@EnumValueFilter("outdated", isNegated = true)
enum class KotlinVersion(override val schemaValue: String, override val outdated: Boolean = false) : SchemaEnum {
    // https://github.com/JetBrains/kotlin/blob/693c44ee79f62895a9b92bdd60fdd7a9bc29a975/compiler/util/src/org/jetbrains/kotlin/config/LanguageVersionSettings.kt#L633-L648
    Kotlin18("1.8", outdated = true), // oldest supported version in Kotlin 2.2.20, which is our minimum
    Kotlin19("1.9", outdated = true),
    Kotlin20("2.0"),
    Kotlin21("2.1"),
    Kotlin22("2.2"),
    Kotlin23("2.3"),
    Kotlin24("2.4"),
    Kotlin25("2.5"),
    ;

    override fun toString(): String = schemaValue
    companion object Index : EnumMap<KotlinVersion, String>(KotlinVersion::values, KotlinVersion::schemaValue)
}

class KotlinSettings : SchemaNode() {

    @PlatformAgnostic
    @Misnomers("compiler")
    @SchemaDoc("The version of the Kotlin compiler and standard library to use")
    @NotBlank
    val version by value(DefaultVersions.kotlin)

    @CanBeReferenced  // by apiVersion
    @PlatformAgnostic
    @Misnomers("language-version")
    @SchemaDoc("Source compatibility with the specified version of Kotlin")
    val languageVersion by nullableValue<KotlinVersion>()

    @PlatformAgnostic
    @Misnomers("api-version", "sdkVersion", "sdk")
    @SchemaDoc("Allow using declarations only from the specified version of Kotlin bundled libraries")
    val apiVersion by referenceValue(::languageVersion)

    class DefaultIncrementalCompilationTransform : ReferenceNode.TransformFunction<Boolean> {
        override fun transform(node: RefinedTreeNode): Boolean {
            // We don't need to handle when node is not a StringNode, because such invalid nodes are handled by the
            // frontend parsing logic already. We just return any value for this case here (we might not even be called).
            return node is StringNode && ComparableVersion(node.value) >= ComparableVersion("2.4.0")
        }
    }

    @PlatformAgnostic
    @Misnomers("avoidance", "compilation")
    @SchemaDoc("Whether Kotlin code should be compiled incrementally (only recompile what's necessary depending on the changes)")
    val compileIncrementally by referenceValue(
        property = ::version,
        description = "enabled for Kotlin compiler >= 2.4.0",
        transformValue = DefaultIncrementalCompilationTransform(),
    )

    @Misnomers("Werror")
    @SchemaDoc("Turn any warnings into a compilation error")
    val allWarningsAsErrors by value(false)

    @Misnomers("compilation", "arguments", "options")
    @SchemaDoc("Pass any [compiler option](https://kotlinlang.org/docs/compiler-reference.html#compiler-options) directly")
    val freeCompilerArgs by nullableValue<List<TraceableString>>()

    @Misnomers("nowarn")
    @SchemaDoc("Suppress the compiler from displaying warnings during compilation")
    val suppressWarnings by value(false)

    @SchemaDoc("Enables verbose logging output which includes details of the compilation process")
    val verbose by value(false)

    @SchemaDoc("(Only for [native targets](https://kotlinlang.org/docs/native-target-support.html)) " +
            "Additional arguments to pass to the linker during binary building.")
    @PlatformSpecific(Platform.NATIVE)
    @Misnomers("linkerOpts", "arguments")
    val linkerOptions by nullableValue<List<TraceableString>>()

    @SchemaDoc("(Only for [native targets](https://kotlinlang.org/docs/native-target-support.html)) " +
            "Enables emitting debug information. Enabled in debug variants by default.")
    @PlatformSpecific(Platform.NATIVE)
    val debug by nullableValue<Boolean>()

    @SchemaDoc("(Only for [native targets](https://kotlinlang.org/docs/native-target-support.html)) " +
            "Enables compilation optimizations and produce a binary with better runtime performance. " +
            "Enabled in release variants by default.")
    @PlatformSpecific(Platform.NATIVE)
    val optimization by nullableValue<Boolean>()

    @SchemaDoc("Enables the [progressive mode for the compiler](https://kotlinlang.org/docs/compiler-reference.html#progressive)")
    val progressiveMode by value(false)

    @DeprecatedSchema("This kind of compiler arguments is advised against by the Kotlin team, so the convenience of " +
            "this property will be removed in a future version. " +
            "Use `freeCompilerArgs: [-XXLanguage:+feature]` instead if you really must.", isError = true)
    val languageFeatures by nullableValue<List<TraceableString>>()
    
    @SchemaDoc("Usages of API that [requires opt-in](https://kotlinlang.org/docs/opt-in-requirements.html) with a requirement annotation with the given fully qualified name")
    val optIns by nullableValue<List<TraceableString>>()

    @SchemaDoc("[KSP (Kotlin Symbol Processing)](https://github.com/google/ksp) settings.")
    val ksp: KspSettings by nested()

    @Misnomers("kotlinx", "kotlinx-serialization", "kotlinx.serialization")
    @PlatformAgnostic
    @SchemaDoc("Configure [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization)")
    val serialization: SerializationSettings by nested()
    
    @PlatformAgnostic
    @SchemaDoc("Configure the [Kotlin no-arg compiler plugin](https://kotlinlang.org/docs/no-arg-plugin.html)")
    val noArg: NoArgSettings by nested()
    
    @PlatformAgnostic
    @SchemaDoc("Configure the [Kotlin all-open compiler plugin](https://kotlinlang.org/docs/all-open-plugin.html)")
    val allOpen: AllOpenSettings by nested()

    @PlatformAgnostic
    @SchemaDoc("Configure the [Kotlin Dataframe](https://kotlin.github.io/dataframe/home.html) compiler plugin")
    val dataframe: DataFrameSettings by nested()

    @PlatformSpecific(Platform.JS)
    @SchemaDoc("Configure the [Kotlin JS plain objects compiler plugin](https://kotlinlang.org/docs/js-plain-objects.html)")
    val jsPlainObjects: JsPlainObjectsSettings by nested()

    @PlatformAgnostic
    @SchemaDoc("Configure the [Kotlin power-assert compiler plugin](https://kotlinlang.org/docs/power-assert.html)")
    val powerAssert: PowerAssertSettings by nested()

    @Misnomers("kotlinx", "kotlinx-rpc", "kotlinx.rpc")
    @PlatformAgnostic
    @SchemaDoc("Configure the [kotlinx.rpc compiler plugin](https://kotlin.github.io/kotlinx-rpc/)")
    val rpc: KotlinxRpcSettings by nested()

    @PlatformAgnostic
    @SchemaDoc("Configure third party plugins for the Kotlin compiler. Note: IDE support might be limited.")
    val compilerPlugins by value<List<ThirdPartyCompilerPlugin>>(emptyList())
}
