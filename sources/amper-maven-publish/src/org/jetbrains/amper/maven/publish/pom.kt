/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.maven.publish

import org.apache.maven.model.Dependency
import org.apache.maven.model.DependencyManagement
import org.apache.maven.model.Developer
import org.apache.maven.model.License
import org.apache.maven.model.Model
import org.apache.maven.model.Scm
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.codehaus.plexus.util.xml.XmlStreamWriter
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.ancestralPath
import org.jetbrains.amper.frontend.diagnostics.ConflictingMavenDependencyDeclarations
import org.jetbrains.amper.frontend.dr.resolver.toDrMavenCoordinates
import org.jetbrains.amper.frontend.schema.DeveloperInfo
import org.jetbrains.amper.frontend.schema.LicenseInfo
import org.jetbrains.amper.frontend.schema.ScmInfo
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Generates a POM for the given [module] at this [Path].
 *
 * [hasMainArtifact] tells whether this publication contains a main artifact (a jar, a klib, etc.). It must be false
 * for modules that don't produce any (modules without sources, for instance), so that the POM is marked as a
 * POM-only publication instead of pointing at a file that doesn't exist.
 */
fun Path.writePomFor(
    module: AmperModule,
    platform: Platform,
    publicationCoordsOverrides: PublicationCoordinatesOverrides,
    gradleMetadataComment: Boolean,
    hasMainArtifact: Boolean,
) {
    val model = generatePomModel(module, platform, publicationCoordsOverrides, hasMainArtifact)
    writePom(model)

    if (gradleMetadataComment) {
        insertGradleMetadataComment()
    }
}

/**
 * Writes the given Maven [model] as a POM file at this [Path].
 */
fun Path.writePom(model: Model) = XmlStreamWriter(toFile()).use { writer ->
    MavenXpp3Writer().write(writer, model)
}

@Suppress("unused")
private fun Path.insertGradleMetadataComment() {
    // Using MavenXpp3Writer.setFileComment would place the text before <project>, and as a single multiline comment,
    // which doesn't match the format output by Gradle.
    // It doesn't matter for Amper consumers, because we just search for a small substring, but it may matter for 
    // consumers using Gradle, so we're conservative and try to match the format exactly here by placing the comment
    // right before <modelVersion> and with each line as an individual XML comment.

    val contentWithGradleMetadataComment = readText().replace(
        oldValue = "  <modelVersion>",
        newValue = """
                |  <!-- This module was also published with a richer model, Gradle metadata, -->
                |  <!-- which should be used instead. Do not delete the following line which -->
                |  <!-- is to indicate to Gradle or any Gradle module metadata file consumer -->
                |  <!-- that they should prefer consuming it instead. -->
                |  <!-- do_not_remove: published-with-gradle-metadata -->
                |  <modelVersion>
                """.trimMargin()
    )
    writeText(contentWithGradleMetadataComment)
}

private fun getDependencies(
    module: AmperModule,
    platform: Platform,
    publicationCoordsOverrides: PublicationCoordinatesOverrides
): Pair<List<Dependency>, DependencyManagement?> =
    if (platform == Platform.COMMON && module.leafPlatforms.contains(Platform.JVM)) {
        // We make the root pom.xml depend on the JVM-specific artifact of our library.
        // This way, Maven builds can reference KMP libraries without adding the '-jvm' suffix.
        val jvmCoords = module.publicationCoordinates(Platform.JVM)

        val jvmDependency = Dependency()
        jvmDependency.groupId = jvmCoords.groupId
        jvmDependency.artifactId = jvmCoords.artifactId
        jvmDependency.version = jvmCoords.version
        jvmDependency.scope = "compile"

        [jvmDependency] to null
    } else {
        val fragment = module.singleProductionFragmentOrNull(platform)
            ?: error("Cannot generate pom for module '${module.userReadableName}': expected a single fragment for platform $platform")

        val [bomDependencies, regularDependencies] = fragment.ancestralPath()
            .flatMap { it.externalDependencies }
            .partition { it is BomDependency }

        val bomPomDependencies = bomDependencies
            .mapNotNull { it.toPomDependency(platform, publicationCoordsOverrides) }
            .distinctBy { it.managementKey }
        val regularPomDependencies = regularDependencies
            .mapNotNull { it.toPomDependency(platform, publicationCoordsOverrides) }
            .distinctBy { it.managementKey }

        val dependencyManagement = if (bomDependencies.isNotEmpty()) {
            DependencyManagement().apply { dependencies.addAll(bomPomDependencies) }
        } else null

        regularPomDependencies to dependencyManagement
    }

/**
 * Reports dependencies that collapse to the same Maven management key after platform variant selection, but would
 * publish different versions or scopes. The check must use the converted POM dependencies because, for example,
 * `kotlinx-coroutines-core` and `kotlinx-coroutines-core-jvm` have different declared coordinates but the same
 * effective coordinates in a JVM POM.
 *
 * @return whether at least one conflict was reported
 */
context(problemReporter: ProblemReporter)
fun reportConflictingMavenDependencyDeclarations(
    module: AmperModule,
    publicationCoordsOverrides: PublicationCoordinatesOverrides,
    platform: Platform? = null,
): Boolean {
    val conflicts = module.leafFragments
        .filter { !it.isTest && (platform == null || it.platform == platform) }
        .flatMap { leafFragment ->
            leafFragment.ancestralPath()
                .flatMap { it.externalDependencies }
                .filterIsInstance<MavenDependencyBase>()
                .map { dependency ->
                    val pomDependency = dependency.toPomDependency(leafFragment.platform, publicationCoordsOverrides)
                    PublishedMavenDependencyDeclaration(
                        source = dependency,
                        dependency = pomDependency,
                        key = PublishedMavenDependencyKey(
                            managementKey = pomDependency.managementKey,
                            isBom = dependency is BomDependency,
                        ),
                    )
                }
                .groupBy { it.key }
                .mapNotNull { [key, declarations] ->
                    val distinctDeclarations = declarations.distinctBy { it.semantics }
                    if (distinctDeclarations.size < 2) {
                        null
                    } else {
                        ConflictingPublishedMavenDependency(
                            managementKey = key.managementKey,
                            leafFragmentName = leafFragment.name,
                            declarations = distinctDeclarations,
                        )
                    }
                }
        }

    for (conflict in conflicts) {
        val declaration = conflict.declarations[0]
        val conflictingDeclaration = conflict.declarations[1]
        problemReporter.reportMessage(
            ConflictingMavenDependencyDeclarations(
                dependency = declaration.source,
                managementKey = conflict.managementKey,
                leafFragmentName = conflict.leafFragmentName,
                version = declaration.semantics.version,
                scope = declaration.semantics.scope,
                conflictingVersion = conflictingDeclaration.semantics.version,
                conflictingScope = conflictingDeclaration.semantics.scope,
            )
        )
    }
    return conflicts.isNotEmpty()
}

private data class PublishedMavenDependencyKey(
    val managementKey: String,
    // BOMs and regular dependencies are written to separate POM sections and are deduplicated separately.
    val isBom: Boolean,
)

private data class PublishedMavenDependencyDeclaration(
    val source: MavenDependencyBase,
    val dependency: Dependency,
    val key: PublishedMavenDependencyKey,
) {
    val semantics = PublishedMavenDependencySemantics(
        version = dependency.version,
        scope = dependency.scope,
    )
}

private data class PublishedMavenDependencySemantics(
    val version: String?,
    val scope: String,
)

private data class ConflictingPublishedMavenDependency(
    val managementKey: String,
    val leafFragmentName: String,
    val declarations: List<PublishedMavenDependencyDeclaration>,
)

private fun generatePomModel(
    module: AmperModule,
    platform: Platform,
    publicationCoordsOverrides: PublicationCoordinatesOverrides,
    hasMainArtifact: Boolean,
): Model {
    val coords = module.publicationCoordinates(platform)
    val fragment = module.singleProductionFragmentOrNull(platform)
        ?: error("Cannot generate pom for module '${module.userReadableName}': expected a single fragment for platform $platform")
    val publishSettings = fragment.settings.publishing

    val [regularPomDependencies, dependencyManagement] = getDependencies(module, platform, publicationCoordsOverrides)

    val model = Model()
    model.modelVersion = "4.0.0"
    
    // "$groupId:$artifactId" is "common practice" for the <name> field according to Sonatype's website:
    // https://central.sonatype.org/publish/requirements/#project-name-description-and-url
    // However, that's not what I found empirically in Maven Central in general. Rather, the module name is used.
    model.name = publishSettings.pom.name ?: module.userReadableName
    // TODO Maybe strip the Markdown syntax when we fall back to the (Markdown) module.description?
    model.description = publishSettings.pom.description ?: module.description
    model.url = publishSettings.pom.url

    model.groupId = coords.groupId
    model.artifactId = coords.artifactId
    model.version = coords.version
    model.dependencies.addAll(regularPomDependencies)

    if (dependencyManagement != null) {
        model.dependencyManagement = dependencyManagement
    }

    model.licenses = publishSettings.pom.licenses.map { it.toMavenLicense() }
    model.developers = publishSettings.pom.developers.map { it.toMavenDeveloper() }
    model.scm = publishSettings.pom.scm.toMavenScmOrNull()
    model.packaging = when {
        // Maven-based consumers locate the main artifact using the packaging type and consider the whole module
        // missing when the corresponding file is absent (this is what Gradle's mavenLocal() does, for instance).
        // Without a main artifact, this publication only provides dependencies, just like a POM-only publication.
        !hasMainArtifact -> "pom"
        platform == Platform.COMMON -> "pom"
        platform == Platform.ANDROID -> "aar"
        platform == Platform.JVM -> "jar"
        else -> "klib"
    }

    return model
}

private fun ScmInfo.toMavenScmOrNull(): Scm? {
    // Avoid an empty <scm /> tag if the SCM properties aren't set
    if (url == null && connection == null && developerConnection == null) {
        return null
    }
    return Scm().apply {
        url = this@toMavenScmOrNull.url
        connection = this@toMavenScmOrNull.connection
        developerConnection = this@toMavenScmOrNull.developerConnection
    }
}

private fun LicenseInfo.toMavenLicense(): License = License().apply {
    name = this@toMavenLicense.name
    url = this@toMavenLicense.url
}

private fun DeveloperInfo.toMavenDeveloper(): Developer = Developer().apply {
    id = this@toMavenDeveloper.id
    name = this@toMavenDeveloper.name
    url = this@toMavenDeveloper.url
    email = this@toMavenDeveloper.email
    organization = this@toMavenDeveloper.organization
    organizationUrl = this@toMavenDeveloper.organizationUrl
}

private fun Notation.toPomDependency(
    platform: Platform,
    publicationCoordsOverrides: PublicationCoordinatesOverrides,
): Dependency? = when (this) {
    is MavenDependencyBase -> toPomDependency(platform, publicationCoordsOverrides)
    is LocalModuleDependency -> toPomDependency(platform)
    is LocalSwiftPMDependencyNotation,
    is RemoteSwiftPMDependencyNotation -> null
    is DefaultScopedNotation -> error("Dependency type ${this::class.simpleName} is not supported for pom.xml publication")
}

private fun LocalModuleDependency.toPomDependency(platform: Platform): Dependency {
    val coords = module.publicationCoordinates(platform)

    val dependency = Dependency()
    dependency.groupId = coords.groupId
    dependency.artifactId = coords.artifactId
    dependency.version = coords.version
    dependency.scope = mavenScopeName()
    return dependency
}

private fun AmperModule.singleProductionFragmentOrNull(platform: Platform) = if (platform == Platform.COMMON) {
    fragments.singleOrNull { !it.isTest && it.fragmentDependencies.isEmpty() }   
} else {
    leafFragments.singleOrNull { !it.isTest && it.platforms == setOf(platform) }
}

private fun MavenDependencyBase.toPomDependency(
    platform: Platform,
    publicationCoordsOverrides: PublicationCoordinatesOverrides,
): Dependency {
    // Each platform POM must declare the variant of the dependency that this very platform resolves to,
    // because consumers of a platform POM don't have Gradle metadata to select a variant themselves.
    val effectiveCoordinates = publicationCoordsOverrides.actualCoordinatesFor(toDrMavenCoordinates(), platform)

    val dependency = Dependency()
    dependency.groupId = effectiveCoordinates.groupId
    dependency.artifactId = effectiveCoordinates.artifactId
    dependency.version = effectiveCoordinates.version
    dependency.classifier = effectiveCoordinates.classifier
    dependency.scope = when (this) {
        is MavenDependency -> mavenScopeName()
        is BomDependency -> "import"
    }
    dependency.type = when (this) {
        is MavenDependency -> effectiveCoordinates.packagingType ?: "jar"
        is BomDependency -> "pom"
    }
    return dependency
}

/**
 * Returns the Maven scope name that corresponds to the settings of this dependency, but only from the perspective of
 * consuming this dependency as a transitive dependency brought by the declaring module into some consumer module.
 * We're not interested here in using this Maven scope to compile or run the declaring module itself with Maven.
 *
 * Maven scopes are described here:
 * https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#dependency-scope
 *
 * The transitive implications are described here:
 * https://www.baeldung.com/maven-dependency-scopes#scope-and-transitivity
 *
 * In short, in a published pom, here are the implications of declaring each scope on a dependency for consumers:
 *
 * | publication \ consumer scope | compile           | runtime           | provided          |
 * | -----------------------------|-------------------|-------------------|-------------------|
 * | compile                      | C & R classpaths  | runtime classpath | runtime classpath |
 * | runtime                      | runtime classpath | runtime classpath | runtime classpath |
 * | provided                     | not visible       | not visible       | not visible       |
 *
 * Technically, there is no point in adding "provided" dependencies to the published pom.xml.
 */
private fun DefaultScopedNotation.mavenScopeName(): String = when {
    compile && runtime && exported   /* Gradle: api            */ -> "compile"
    compile && runtime && !exported  /* Gradle: implementation */ -> "runtime" // consumers should not get it on their compile classpath
    compile && !runtime && exported  /* Gradle: compileOnlyApi */ -> "compile" // Maven cannot represent this. "provided" would not be exported to consumers.
    compile && !runtime && !exported /* Gradle: compileOnly    */ -> "provided"
    !compile && runtime && exported  /* Gradle: NO EQUIVALENT  */ -> "runtime" // TODO should we forbid this case in the frontend? it doesn't make any difference to export a runtime-only dependency
    !compile && runtime && !exported /* Gradle: runtimeOnly    */ -> "runtime" // consumers should not get it on their compile classpath
    else -> error("Dependency '${userReadableCoordinates()}' is neither compile nor runtime")
}

private fun DefaultScopedNotation.userReadableCoordinates(): String = when (this) {
    is MavenDependency -> coordinates.toString()
    is LocalModuleDependency -> module.userReadableName
    else -> toString()
}
