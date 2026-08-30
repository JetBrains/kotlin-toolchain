/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.EnumMap
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaEnum
import org.jetbrains.amper.frontend.api.CustomSchemaDeclaration
import org.jetbrains.amper.frontend.api.HiddenFromCompletion
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.NotBlank
import org.jetbrains.amper.frontend.api.PlatformAgnostic
import org.jetbrains.amper.frontend.api.PlatformSpecific
import org.jetbrains.amper.frontend.api.ProductTypeSpecific
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.schema.kotlin.KotlinSettings
import org.jetbrains.amper.frontend.userGuideUrl

@SchemaDoc("JUnit version that is used for the module tests")
enum class JUnitVersion(override val schemaValue: String, override val outdated: Boolean = false) : SchemaEnum {
    JUNIT4("junit-4"),
    JUNIT5("junit-5"),
    NONE("none");
    companion object Index : EnumMap<JUnitVersion, String>(JUnitVersion::values, JUnitVersion::schemaValue)
}

class Settings : SchemaNode() {

    // Some stuff in here (JDK) can be used for native as well, so it's not specific to JVM and Android.
    // It's also not platform-agnostic as a whole because some things in it can be set differently (e.g. main class or
    // tests) on JVM and Android.
    @SchemaDoc("Settings that apply to all JVM-related sources (both Java and Kotlin)")
    val jvm: JvmSettings by nested()

    @SchemaDoc("Settings to configure the compilation of Java sources")
    @PlatformSpecific(Platform.JVM, Platform.ANDROID)
    val java: JavaSettings by nested()

    @SchemaDoc("Settings to configure the compilation of Kotlin sources")
    val kotlin: KotlinSettings by nested()

    @SchemaDoc("Android toolchain and platform settings")
    @PlatformSpecific(Platform.ANDROID)
    val android: AndroidSettings by nested()

    @PlatformAgnostic
    @SchemaDoc("[Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) framework. " +
            "Read more about [Compose configuration]($userGuideUrl/builtin-tech/compose-multiplatform/)")
    val compose: ComposeSettings by nested()

    @Misnomers("test")
    @SchemaDoc("JUnit test runner on the JVM and Android platforms. " +
            "Read more about [testing support]($userGuideUrl/testing/)")
    @PlatformSpecific(Platform.JVM, Platform.ANDROID)
    val junit by value(JUnitVersion.JUNIT5)

    @SchemaDoc("Publishing settings")
    @PlatformAgnostic
    val publishing: PublishingSettings by nested()

    @SchemaDoc("Native applications settings")
    @PlatformSpecific(Platform.NATIVE)
    @ProductTypeSpecific(ProductType.MACOS_APP, ProductType.LINUX_APP, ProductType.WINDOWS_APP)
    val native by nullableValue<NativeSettings>()

    @SchemaDoc("Ktor server settings")
    val ktor: KtorSettings by nested()

    @PlatformSpecific(Platform.JVM)
    @SchemaDoc("Spring Boot settings")
    val springBoot: SpringBootSettings by nested()

    @PlatformSpecific(Platform.JVM, Platform.ANDROID)
    @SchemaDoc("Lombok settings")
    val lombok: LombokSettings by nested()

    /** no documentation here - the block with Amper internal undesigned settings */
    @PlatformAgnostic
    @HiddenFromCompletion
    val internal: InternalSettings by nested()
}

/**
 * All the plugin settings are linked under here, dynamically.
 * The properties are plugin IDs that are made available in the project.
 *
 * @see org.jetbrains.amper.plugins.schema.model.PluginData
 */
@CustomSchemaDeclaration
class PluginSettings : SchemaNode()

class ComposeSettings : SchemaNode() {

    @Shorthand
    @SchemaDoc("Enables the Compose compiler plugin, runtime dependency, and library catalog")
    val enabled by value(default = false)

    @SchemaDoc("The Compose plugin version")
    @NotBlank
    val version by value(DefaultVersions.compose)

    @SchemaDoc("Compose Resources settings")
    val resources: ComposeResourcesSettings by nested()

    @SchemaDoc("Experimental Compose settings")
    val experimental: ComposeExperimentalSettings by nested()
}

class ComposeResourcesSettings : SchemaNode() {
    @SchemaDoc(
        "A unique identifier for the resources in the current module.<br>" +
                "Used as package for the generated Res class and for isolating resources in the final artifact."
    )
    val packageName by value(default = "")

    @SchemaDoc(
        "The name of the Kotlin object on which all the resource accessors are generated. `Res` by default. " +
                "Can be customized to avoid name clashes when using resources from multiple modules."
    )
    @NotBlank
    val nameOfResClass by value(default = "Res")

    @SchemaDoc(
        "Whether the generated resources accessors should be exposed to other modules (public) or " +
                "internal."
    )
    val exposedAccessors by value(default = false)
}

class ComposeExperimentalSettings: SchemaNode() {

    @PlatformSpecific(Platform.JVM) // we can only use Hot Reload on JVM for now, better warn users about it
    @SchemaDoc("Experimental Compose hot-reload settings")
    val hotReload: ComposeExperimentalHotReloadSettings by nested()
}

class ComposeExperimentalHotReloadSettings: SchemaNode() {
    @SchemaDoc("The version of the Compose Hot Reload toolchain to use.")
    @NotBlank
    val version by value(default = DefaultVersions.composeHotReload)
}

class NativeSettings : SchemaNode() {

    // TODO other options from NativeApplicationPart
    @SchemaDoc("The fully-qualified name of the application's entry point function")
    @NotBlank
    val entryPoint by nullableValue<String>()
}

class KtorSettings: SchemaNode() {

    @Shorthand
    @SchemaDoc("Enable the Ktor server framework. This is just a convenience to generate library catalog entries for Ktor libraries.")
    val enabled by value(default = false)

    @SchemaDoc("The Ktor version used for the BOM and in the generated library catalog entries")
    @NotBlank
    val version by value(default = DefaultVersions.ktor)

    @SchemaDoc("Whether to apply the Ktor BOM or not")
    val applyBom by value(default = true)
}

class SpringBootSettings: SchemaNode() {

    @Shorthand
    @SchemaDoc("Enables the Spring Boot framework support. This configures some Kotlin compiler plugins with Spring presets, and generates a built-in catalog with ")
    val enabled by value(default = false)

    @SchemaDoc("The Spring Boot version, which is used for the BOM and in the generated library catalog entries")
    @NotBlank
    val version by value(default = DefaultVersions.springBoot)

    @SchemaDoc("Whether to apply the spring-boot-dependencies BOM or not")
    val applyBom by value(default = true)
}

class LombokSettings: SchemaNode() {
    
    @Shorthand
    @SchemaDoc("Enables Lombok")
    val enabled by value(default = false)

    @SchemaDoc("The version of Lombok to use for the runtime library and the annotation processor")
    @NotBlank
    val version by value(default = DefaultVersions.lombok)
}

class InternalSettings : SchemaNode() {
    /**
     * A temporary internal solution that we have for `exclude` in DR until we have a properly designed support.
     * a list of "<group>:<artifact>" strings.
     * Each dependency in the module that matches any such entry is excluded from DR completely.
     * */
    val excludeDependencies: List<String> by value(default = emptyList())
}