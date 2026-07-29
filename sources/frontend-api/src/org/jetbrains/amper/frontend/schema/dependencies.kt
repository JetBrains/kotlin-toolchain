/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.EnumMap
import org.jetbrains.amper.frontend.MavenCoordinates
import org.jetbrains.amper.frontend.SchemaEnum
import org.jetbrains.amper.frontend.api.EnumOrderSensitive
import org.jetbrains.amper.frontend.api.ExternalDependencyNotation
import org.jetbrains.amper.frontend.api.FromKeyAndTheRestIsNested
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.Traceable
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependencySchema
import org.jetbrains.amper.frontend.userGuideUrl
import java.nio.file.Path

private const val dependenciesGuideUrl = "$userGuideUrl/dependencies"

@EnumOrderSensitive
enum class DependencyScope(
    override val schemaValue: String,
    val runtime: Boolean,
    val compile: Boolean,
    override val outdated: Boolean = false,
) : SchemaEnum {
    COMPILE_ONLY("compile-only", runtime = false, compile = true),
    RUNTIME_ONLY("runtime-only", runtime = true, compile = false),
    ALL("all", runtime = true, compile = true);

    companion object : EnumMap<DependencyScope, String>(DependencyScope::values, DependencyScope::schemaValue)
}

// TODO Break this hierarchy into two:
//  - DependencyNotation: MavenNotation, CatalogNotation, LocalNotation (in future replaced as just reference)
//  - Dependency: ScopedDependency, BomDependency (if we need any special meaning here for Bom).
//  .
//  Also, by breaking this hierarchy we can replace KspDependencies by just notation.
//  Also, it may contradict "constructor args" approach from AmperLang.
sealed class Dependency : SchemaNode()

sealed class ScopedDependency : Dependency() {
    // TODO Replace exported flag by new scope (rethink scopes).
    @Shorthand
    @SchemaDoc("Whether a dependency should be [visible as a part of a published API]($dependenciesGuideUrl/#transitivity)")
    val exported by value(false)

    @Shorthand
    @SchemaDoc("When the dependency should be used. Read more about the [dependency scopes]($dependenciesGuideUrl/#scopes)")
    val scope by value(DependencyScope.ALL)
}

/**
 * Hierarchical notation for dependencies without scope, that is identical to [Dependency].
 */
// TODO See TODO on [Dependency].
sealed class UnscopedDependency : SchemaNode()

/**
 * Helper interface to force implementors to have maven coordinates matching fields.
 */
interface SchemaMavenCoordinates : Traceable {
    companion object {
        // Order matters!
        val properties = listOf(
            SchemaMavenCoordinates::groupId.name,
            SchemaMavenCoordinates::artifactId.name,
            SchemaMavenCoordinates::version.name,
            SchemaMavenCoordinates::classifier.name,
        )
    }
    
    val groupId: String
    val artifactId: String
    val version: String?
    val classifier: String?
}

val SchemaMavenCoordinates.coordinates get() = "$groupId:$artifactId:$version"

@ExternalDependencyNotation
class ExternalMavenDependency : ScopedDependency(), SchemaMavenCoordinates {
    @Misnomers("groupId")
    override val groupId by value<String>()

    @Misnomers("artifact")
    override val artifactId by value<String>()
    override val version by nullableValue<String>()
    override val classifier by nullableValue<String>()
}

class InternalDependency : ScopedDependency() {

    @SchemaDoc("Dependency [on another module]($dependenciesGuideUrl/#module-dependencies) in the codebase")
    @FromKeyAndTheRestIsNested
    val path by value<Path>()
}

class CatalogDependency : ScopedDependency() {

    @SchemaDoc("Dependency from [a library catalog]($dependenciesGuideUrl/#library-catalogs)")
    @FromKeyAndTheRestIsNested
    val catalogKey by value<String>()
}

class UnscopedModuleDependency : UnscopedDependency() {
    @FromKeyAndTheRestIsNested
    val path by value<Path>()
}

sealed class UnscopedExternalDependency : UnscopedDependency()

@ExternalDependencyNotation
class UnscopedExternalMavenDependency : UnscopedExternalDependency(), SchemaMavenCoordinates {
    @Misnomers("group")
    override val groupId by value<String>()

    @Misnomers("artifact")
    override val artifactId by value<String>()
    override val version by nullableValue<String>()
    override val classifier by nullableValue<String>()
}

class UnscopedCatalogDependency : UnscopedExternalDependency() {

    // Actual usage of this property is indirect and located within [CatalogVersionsSubstitutor] within the tree.
    // The value of this property is to provide the schema.
    @Suppress("unused")
    @FromKeyAndTheRestIsNested
    val catalogKey by value<String>()
}

class UnscopedBomDependency : UnscopedDependency() {
    val bom by value<UnscopedExternalDependency>()
}

class BomDependency : Dependency() {
    val bom by value<UnscopedExternalDependency>()
}

class RemoteSwiftPMDependencySchema : Dependency() {
    val swiftPackage by value<SwiftPMDependencySchema.Remote>()
}

class LocalSwiftPMDependencySchema : Dependency() {
    val localSwiftPackage by value<SwiftPMDependencySchema.Local>()
}

fun SchemaMavenCoordinates.toMavenCoordinates() = MavenCoordinates(
    groupId = groupId,
    artifactId = artifactId,
    version = version?.let {
        TraceableString(
            it,
            (this as SchemaNode).getDelegate(SchemaMavenCoordinates::version.name).trace,
        )
    },
    classifier = classifier,
    packagingType = null,
    trace = trace,
)

/**
 * See [String.toMavenCoordinates].
 */
fun TraceableString.toMavenCoordinates() = value.toMavenCoordinates(trace)

/**
 * Splits this [TraceableString] into its [SchemaMavenCoordinates] components.
 *
 * This [TraceableString] must respect the full Maven format with 2 to 4 parts delimited with `:`, and with an optional
 * packaging type appended after `@` at the end:
 *
 * ```
 * groupId:artifactId[:version][:classifier][@packagingType]
 * ```
 */
fun String.toMavenCoordinates(trace: Trace): MavenCoordinates {
    val coordsAndPackaging = trim().split("@")
    val coords = coordsAndPackaging.first().split(":")
    val packagingType = coordsAndPackaging.getOrNull(1)

    check(coords.size in 2..4) {
        "Coordinates should have between 2 and 4 parts, but got ${coords.size}: $this. " +
                "Ensure that the coordinates were properly validated in the parser."
    }
    return MavenCoordinates(
        groupId = coords[0],
        artifactId = coords[1],
        version = coords.getOrNull(2)?.let { TraceableString(it, trace) },
        classifier = coords.getOrNull(3),
        packagingType = packagingType,
        trace = trace,
    )
}
