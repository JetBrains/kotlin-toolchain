/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.kotlin.plugins

import org.jetbrains.amper.frontend.api.NotBlank
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.schema.UnscopedExternalDependency

class ThirdPartyCompilerPlugin : SchemaNode() {
    @SchemaDoc("The ID of this compiler plugin, used to pass options. " +
            "It is defined by the `pluginId` property in the `CommandLineProcessor` implementation of the plugin. " +
            "If the plugin is also implemented as a Gradle plugin, its ID can also be found in " +
            "`getCompilerPluginId()` in the corresponding `KotlinCompilerPluginSupportPlugin` subclass.")
    @NotBlank
    val id by value<String>()

    @SchemaDoc("The compiler plugin dependency, in the form of `groupId:artifactId:version` Maven coordinates, or " +
            "a catalog reference.")
    val dependency by value<UnscopedExternalDependency>() // only external maven dependencies are supported, not modules

    @SchemaDoc("The options to pass to this compiler plugin, as a key-value map.")
    val options by value<Map<TraceableString, TraceableString>>(emptyMap())
}
