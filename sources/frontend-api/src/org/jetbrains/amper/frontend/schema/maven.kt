/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.api.CustomSchemaDeclaration
import org.jetbrains.amper.frontend.api.ExternalDependencyNotation
import org.jetbrains.amper.frontend.api.IgnoreForSchema
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand

@ExternalDependencyNotation
class MavenPlugin : SchemaNode(), SchemaMavenCoordinates {

    @Misnomers("group")
    @SchemaDoc("Maven plugin groupId")
    override val groupId by value<String>()

    @Misnomers("artifact")
    @SchemaDoc("Maven plugin artifactId")
    override val artifactId by value<String>()

    @SchemaDoc("Maven plugin version")
    override val version by nullableValue<String>()

    @IgnoreForSchema
    override val classifier = "jar"

    @SchemaDoc("Maven plugin packaging type")
    override val packagingType by nullableValue<String>()
}

@CustomSchemaDeclaration(MavenMojoSettings::class)
class MavenPluginSettings : SchemaNode()

class MavenMojoSettings : SchemaNode() {

    @Shorthand
    @SchemaDoc("Enabled corresponding maven mojo execution")
    val enabled by value(default = false)
    
    @SchemaDoc("The list of dependencies added to the classpath of the maven mojo execution")
    val dependencies by nullableValue<List<UnscopedExternalMavenDependency>>(default = emptyList())

    @SchemaDoc("The configuration for mojo execution")
    val configuration by nullableValue<MavenMojoConfiguration>()
    
}

@CustomSchemaDeclaration
class MavenMojoConfiguration : SchemaNode()