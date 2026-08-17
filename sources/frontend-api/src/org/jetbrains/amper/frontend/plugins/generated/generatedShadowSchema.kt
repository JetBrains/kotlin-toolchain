// AUTO-MANAGED SOURCE FILE! DO NOT EDIT MANUALLY!
// --------------------------------------------
// Run ExtensibilityApiDeclarationsTest to see if the source needs updating.
//
// @formatter:off
//
@file:Suppress(
    "REDUNDANT_VISIBILITY_MODIFIER",
    "CanConvertToMultiDollarString",
)

package org.jetbrains.amper.frontend.plugins.generated

import java.nio.`file`.Path
import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass
import org.jetbrains.amper.frontend.SchemaEnum
import org.jetbrains.amper.frontend.api.CanBeReferenced
import org.jetbrains.amper.frontend.api.ExternalDependencyNotation
import org.jetbrains.amper.frontend.api.FromKeyAndTheRestIsNested
import org.jetbrains.amper.frontend.api.IgnoreForSchema
import org.jetbrains.amper.frontend.api.PathMark
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.plugins.schema.model.InputOutputMark

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.Dependency`
 */
public sealed class ShadowDependency : SchemaNode()

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.Classpath`
 */
@SchemaDoc(doc = "Use to get a resolved JVM classpath for the list of [dependencies].\n\nThe resulting classpath can be obtained via [resolvedFiles] property.\n\nTo conveniently get the classpath of the *current module* the plugin is enabled in,\nyou can reference the provided values:\n- `${'$'}{module.runtimeClasspath}`\n- `${'$'}{module.compileClasspath}`")
public class ShadowClasspath : SchemaNode() {
    @Shorthand
    @CanBeReferenced
    @SchemaDoc(doc = "Dependencies to resolve.\nVersion conflict resolution may apply if necessary for the given list of dependencies.\n\n")
    public val dependencies: List<ShadowDependency> by value()

    @CanBeReferenced
    @SchemaDoc(doc = "Resolution scope to use to resolve [dependencies].")
    public val scope: ShadowResolutionScope by value(default = ShadowResolutionScope.Runtime)

    @IgnoreForSchema
    @SchemaDoc(doc = "Resolved classpath files.")
    public lateinit var resolvedFiles: List<Path>
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.CompilationArtifact`
 */
@SchemaDoc(doc = "Provides the compilation result of the [given][from] module.\n\nWarning: only JVM platform is currently supported.")
public class ShadowCompilationArtifact : SchemaNode() {
    @CanBeReferenced
    @SchemaDoc(doc = "The local module to get the compilation result from.")
    public val from: ShadowDependencyLocal by nested()

    @CanBeReferenced
    @SchemaDoc(doc = "The kind of the compilation artifact.")
    public val kind: ShadowCompilationArtifactKind by value()

    @IgnoreForSchema
    @SchemaDoc(doc = "Path to the compilation artifact.\nThe contents are dependent on the [kind].")
    public lateinit var artifact: Path
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.Dependency.Local`
 */
@SchemaDoc(doc = "A dependency on a local module in the project. \nCan be constructed from a path string, like `../module-name` or `\".\"`.\nIf not started with `\".\"` then it's treated like an external maven dependency.")
public class ShadowDependencyLocal : ShadowDependency() {
    @FromKeyAndTheRestIsNested
    @PathMark(InputOutputMark.ValueOnly)
    @SchemaDoc(doc = "Path to the module root directory.")
    public val modulePath: Path by value()
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.Dependency.Maven`
 */
@SchemaDoc(doc = "External Maven dependency. \nCan be constructed from Maven coordinates string, like `com.example:artifact:1.0.0`, \nor from a full YAML form, like:\n```yaml\ngroupId: com.example\nartifactId: artifact\nversion: 1.0.0\n```")
@ExternalDependencyNotation
public class ShadowDependencyMaven : ShadowDependency() {
    @CanBeReferenced
    @SchemaDoc(doc = "External Maven artifact groupId.")
    public val groupId: String by value()

    @CanBeReferenced
    @SchemaDoc(doc = "External Maven artifact artifactId.")
    public val artifactId: String by value()

    @CanBeReferenced
    @SchemaDoc(doc = "External Maven artifact version.")
    public val version: String by value()

    @CanBeReferenced
    @SchemaDoc(doc = "Optional Maven artifact classifier. Empty by default.")
    public val classifier: String? by nullableValue()

    @CanBeReferenced
    @SchemaDoc(doc = "Optional Maven artifact packaging type. Jar by default.")
    public val packagingType: String? by nullableValue()
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.ModuleSources`
 */
@SchemaDoc(doc = "Use to get module [source directories][sourceDirectories] from the module.\nTakes the source layout option into account.\n\nUse [kind] to get different kinds of module sources.\n\nCurrently, only JVM non-test sources are supported.")
public class ShadowModuleSources : SchemaNode() {
    @CanBeReferenced
    @SchemaDoc(doc = "Module to get source directories for.")
    public val from: ShadowDependencyLocal by nested()

    @CanBeReferenced
    @SchemaDoc(doc = "What kind of sources to request.")
    public val kind: ShadowSourcesKind by value(default = ShadowSourcesKind.KotlinJavaSources)

    @CanBeReferenced
    @SchemaDoc(doc = "`false` if only user sources are to be included with respect to the project layout.\n`true` if sources generated by other tasks (plugins, KSP, etc.) are also to be included.")
    public val includeGenerated: Boolean by value(default = false)

    @IgnoreForSchema
    @SchemaDoc(doc = "Resulting source directories for the [module][from].\nNot all of them may exist.")
    public lateinit var sourceDirectories: List<Path>
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.CompilationArtifact.Kind`
 */
public enum class ShadowCompilationArtifactKind(
    override val schemaValue: String,
) : SchemaEnum {
    @SchemaDoc(doc = "A single JAR compiled from module sources.\nIf there is a need to request unpacked classes, use [Classes] instead.")
    Jar("jar"),
    @SchemaDoc(doc = "A directory containing all the module's compiled classes.")
    Classes("classes"),
    ;
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.ResolutionScope`
 */
public enum class ShadowResolutionScope(
    override val schemaValue: String,
) : SchemaEnum {
    @SchemaDoc(doc = "`runtime` maven-like dependency scope.\nIncludes the dependencies that must be present in the runtime classpath.")
    Runtime("runtime"),
    @SchemaDoc(doc = "`compile` maven-like dependency scope.\nIncludes the dependencies that must be present in the compilation classpath.\n\nDoes not include the module compilation results for [local dependencies][Dependency.Local].")
    Compile("compile"),
    ;
}

/**
 * Generated!
 * Shadow for `org.jetbrains.amper.plugins.SourcesKind`
 */
public enum class ShadowSourcesKind(
    override val schemaValue: String,
) : SchemaEnum {
    @SchemaDoc(doc = "Kotlin + Java source directories, e.g., `src`, `src@jvm`, ...")
    KotlinJavaSources("kotlin-java-sources"),
    @SchemaDoc(doc = "Java resources directories, e.g., `resources`, `resources@jvm`, ...")
    Resources("resources"),
    ;
}

public object ShadowMaps {
    public val PublicInterfaceToShadowNodeClass: Map<String, KClass<*>> = mapOf(
            "org.jetbrains.amper.plugins.Classpath" to ShadowClasspath::class,
            "org.jetbrains.amper.plugins.CompilationArtifact" to ShadowCompilationArtifact::class,
            "org.jetbrains.amper.plugins.Dependency.Local" to ShadowDependencyLocal::class,
            "org.jetbrains.amper.plugins.Dependency.Maven" to ShadowDependencyMaven::class,
            "org.jetbrains.amper.plugins.ModuleSources" to ShadowModuleSources::class,
            "org.jetbrains.amper.plugins.Dependency" to ShadowDependency::class,
            "org.jetbrains.amper.plugins.CompilationArtifact.Kind" to ShadowCompilationArtifactKind::class,
            "org.jetbrains.amper.plugins.ResolutionScope" to ShadowResolutionScope::class,
            "org.jetbrains.amper.plugins.SourcesKind" to ShadowSourcesKind::class,
            )

    public val ShadowNodeClassToPublicReflectionName: Map<KClass<*>, String> = mapOf(
            ShadowClasspath::class to "org.jetbrains.amper.plugins.Classpath",
            ShadowCompilationArtifact::class to "org.jetbrains.amper.plugins.CompilationArtifact",
            ShadowDependencyLocal::class to "org.jetbrains.amper.plugins.Dependency${'$'}Local",
            ShadowDependencyMaven::class to "org.jetbrains.amper.plugins.Dependency${'$'}Maven",
            ShadowModuleSources::class to "org.jetbrains.amper.plugins.ModuleSources",
            ShadowDependency::class to "org.jetbrains.amper.plugins.Dependency",
            ShadowCompilationArtifactKind::class to "org.jetbrains.amper.plugins.CompilationArtifact${'$'}Kind",
            ShadowResolutionScope::class to "org.jetbrains.amper.plugins.ResolutionScope",
            ShadowSourcesKind::class to "org.jetbrains.amper.plugins.SourcesKind",
            )
}
