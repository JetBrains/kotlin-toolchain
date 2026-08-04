/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.maven

import org.codehaus.plexus.util.Os
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyResolutionBundle
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependency
import org.jetbrains.amper.dependency.resolution.MavenDependencyImpl
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.Settings
import org.jetbrains.amper.dependency.resolution.coordinates
import org.jetbrains.amper.dependency.resolution.createOrReuseDependency
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.ProjectHasMoreThanTenAncestors
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.UnableToParsePom
import org.jetbrains.amper.dependency.resolution.diagnostics.DiagnosticReporter
import org.jetbrains.amper.dependency.resolution.diagnostics.asMessage
import org.jetbrains.amper.dependency.resolution.mavenCoordinatesTrimmed
import org.jetbrains.amper.dependency.resolution.metadata.xml.ActivationFile
import org.jetbrains.amper.dependency.resolution.metadata.xml.ActivationOS
import org.jetbrains.amper.dependency.resolution.metadata.xml.ActivationProperty
import org.jetbrains.amper.dependency.resolution.metadata.xml.Dependencies
import org.jetbrains.amper.dependency.resolution.metadata.xml.Dependency
import org.jetbrains.amper.dependency.resolution.metadata.xml.DependencyManagement
import org.jetbrains.amper.dependency.resolution.metadata.xml.Parent
import org.jetbrains.amper.dependency.resolution.metadata.xml.Profile
import org.jetbrains.amper.dependency.resolution.metadata.xml.ProfileActivation
import org.jetbrains.amper.dependency.resolution.metadata.xml.Project
import org.jetbrains.amper.dependency.resolution.metadata.xml.Properties
import org.jetbrains.amper.dependency.resolution.metadata.xml.expandTemplate
import org.jetbrains.amper.dependency.resolution.metadata.xml.expandTemplates
import org.jetbrains.amper.dependency.resolution.metadata.xml.managementKey
import org.jetbrains.amper.dependency.resolution.metadata.xml.parsePom
import org.jetbrains.amper.dependency.resolution.metadata.xml.plus
import org.jetbrains.amper.dependency.resolution.metadata.xml.systemPropertyOrEnvironmentVariable
import org.jetbrains.amper.dependency.resolution.resolveSingleVersion
import org.jetbrains.amper.incrementalcache.DynamicInputs
import org.jetbrains.amper.incrementalcache.getDynamicInputs
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.regex.Pattern
import kotlin.io.path.Path

private val logger = LoggerFactory.getLogger("dr/maven/pomResolver.kt")

/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

/**
 * This method resolves effective Project based on library pom.xml.
 * It takes text of pom.xml related to the maven dependency and resolve all its elements taken data
 * defined in parents, profiles, BOMs, properties into account.
 */
internal suspend fun MavenDependencyImpl.resolvePom(
    text: String, context: Context, level: ResolutionLevel, diagnosticsReporter: DiagnosticReporter,
): Project? {
    return try {
        computeIfAbsentInResolutionCache(context, "resolvedPomProject") {
            parsePom(text).resolve(context, level, diagnosticsReporter)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = UnableToParsePom.asMessage(
            pom,
            extra = DependencyResolutionBundle.message("extra.exception", e),
            exception = e,
        )
        logger.warn(message.message, e)
        diagnosticsReporter.addMessage(message)
        return null
    }
}

/**
 * Resolves a Maven project by recursively substituting references to parent projects and templates
 * with actual values.
 * Additionally, dependency versions are defined using dependency management.
 */
private suspend fun Project.resolve(
    context: Context,
    resolutionLevel: ResolutionLevel,
    diagnosticsReporter: DiagnosticReporter,
    depth: Int = 0,
    origin: Project = this,
): Project {
    if (depth > 10) {
        diagnosticsReporter.addMessage(ProjectHasMoreThanTenAncestors.asMessage(origin))
        return this
    }
    val parentProject = parent
        ?.let { context.createOrReuseDependency(it.coordinates, isBom = false) }
        ?.takeIf { it.pom.isDownloadedOrDownload(resolutionLevel, context, diagnosticsReporter) }
        ?.let {
            val text = it.pom.readText()
            it.parsePom(text).resolve(context, resolutionLevel, diagnosticsReporter, depth + 1, origin)
        }
    val dynamicInputs = getDynamicInputs()

    val withoutProfilesProperties: Properties? = parentProject?.properties + properties
    val activeProfiles = this.activeProfiles(context.settings, withoutProfilesProperties)

    val project = if (parentProject != null) {
        copy(
            groupId = groupId ?: parentProject.groupId,
            artifactId = artifactId ?: parentProject.artifactId,
            version = version ?: parentProject.version,
            dependencies = activeProfiles.dependencies() + dependencies + parentProject.dependencies,
            properties = withoutProfilesProperties + activeProfiles.properties(),
        ).let {
            val importedDependencyManagement =
                it.resolveImportedDependencyManagement(context, resolutionLevel, diagnosticsReporter, depth, dynamicInputs)
            it.copy(
                // Dependencies declared directly in pom.xml dependencyManagement section take precedence over directly imported dependencies,
                // both in turn take precedence over parent dependencyManagement
                dependencyManagement = activeProfiles.dependencyManagement() + dependencyManagement +
                        importedDependencyManagement + parentProject.dependencyManagement,
            )
        }
    } else if (parent != null && (groupId == null || artifactId == null || version == null)) {
        val importedDependencyManagement =
            resolveImportedDependencyManagement(context, resolutionLevel, diagnosticsReporter, depth, dynamicInputs)
        copy(
            groupId = groupId ?: parent.groupId,
            artifactId = artifactId ?: parent.artifactId,
            version = version ?: parent.version,
            dependencies = activeProfiles.dependencies() + dependencies,
            dependencyManagement = activeProfiles.dependencyManagement() + dependencyManagement + importedDependencyManagement,
            properties = withoutProfilesProperties + activeProfiles.properties(),
        )
    } else {
        val importedDependencyManagement =
            resolveImportedDependencyManagement(context, resolutionLevel, diagnosticsReporter, depth, dynamicInputs)
        copy(
            dependencies = activeProfiles.dependencies() + dependencies,
            dependencyManagement = activeProfiles.dependencyManagement() + dependencyManagement + importedDependencyManagement,
            properties = withoutProfilesProperties + activeProfiles.properties(),
        )
    }

    val dependencyManagement = project.dependencyManagement?.copy(
        dependencies = project.dependencyManagement.dependencies?.copy(
            dependencies = project.dependencyManagement.dependencies.dependencies
                .map { it.expandTemplates(project, dynamicInputs) }
        )
    )

    val dependencies = project.getEffectiveDependencies(dependencyManagement, dynamicInputs)

    return project
        .expandTemplates(dynamicInputs)
        .copy(
            dependencies = dependencies?.let { Dependencies(it) },
            dependencyManagement = dependencyManagement
        )
}

/**
 * @return raw project dependencies with resolved declarations.
 * Unspecified versions are resolved from the dependencyManagement section (if any),
 * Project properties used in the dependency declaration are substituted with actual values.
 */
private fun Project.getEffectiveDependencies(
    dependencyManagement: DependencyManagement?,
    dynamicInputs: DynamicInputs,
): List<Dependency>? =
    dependencies
    ?.dependencies
    ?.map { it.expandTemplates(this, dynamicInputs) } // expanding properties used in groupId/artifactId
    ?.map { dep ->
        // Version and scope are set in the dependency itself
        if (dep.version != null && dep.scope != null) {
            return@map if (dep.version.resolveSingleVersion() != dep.version) {
                dep.copy(version = dep.version.resolveSingleVersion())
            } else dep
        }
        // Try extracting dependency version and scope from other dependencies with the same group and artifact ID
        dependencyManagement
            ?.dependencies
            ?.dependencies
            ?.find { it.groupId == dep.groupId && it.artifactId == dep.artifactId }
            ?.let { dependencyManagementEntry ->
                return@map dep
                    .let {
                        val dependencyManagementEntryVersion =
                            dependencyManagementEntry.version?.resolveSingleVersion()
                        if (dep.version == null && dependencyManagementEntryVersion != null) it.copy(version = dependencyManagementEntryVersion)
                        else it
                    }.let {
                        if (dep.scope == null && dependencyManagementEntry.scope != null) it.copy(scope = dependencyManagementEntry.scope)
                        else it
                    }
            }
        // Return the dependency as is if no source for version and scope is found
        dep
    }
    ?.map { it.expandTemplates(this, dynamicInputs) }
    // Maven interpolates the model before merging the pom sections; thus, declarations that differ by
    // a property reference only (e.g., by a classifier that expands to an empty value) are a single dependency.
    ?.distinctBy { it.managementKey }

/**
 * Resolve an effective imported dependencyManagement.
 * If several dependencies are imported, then those are merged into a single [DependencyManagement] object.
 * The first declared import dependency takes precedence over the second one and so on.
 *
 * Parent poms of imported dependencies are taken into account
 * (in a standard way of resolving dependencyManagement section)
 * Specification tells about import scope:
 *  "It indicates the dependency is to be replaced with the
 *   effective list of dependencies in the specified POM's <dependencyManagement> section."
 *  (https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#dependency-scope)
 */
private suspend fun Project.resolveImportedDependencyManagement(
    context: Context,
    resolutionLevel: ResolutionLevel,
    diagnosticsReporter: DiagnosticReporter,
    depth: Int,
    dynamicInputs: DynamicInputs,
): DependencyManagement? {
    return dependencyManagement
        ?.dependencies
        ?.dependencies
        ?.map { it.expandTemplates(this, dynamicInputs) }
        ?.mapNotNull {
            if (it.scope == "import" && it.version != null) {
                val dependency = context.createOrReuseDependency(it.coordinates, isBom = true)
                if (dependency.pom.isDownloadedOrDownload(resolutionLevel, context, diagnosticsReporter)) {
                    val text = dependency.pom.readText()
                    val dependencyProject =
                        dependency.parsePom(text).resolve(context, resolutionLevel, diagnosticsReporter, depth + 1)
                    dependencyProject.dependencyManagement
                } else {
                    null
                }
            } else {
                null
            }
        }
        ?.takeIf { it.isNotEmpty() }
        ?.reduce(DependencyManagement::plus)
}


private fun MavenDependency.parsePom(text: String): Project =
    sanitizePom(text, coordinates).parsePom()

private fun sanitizePom(pomText: String, coordinates: MavenCoordinates): String =
    if (coordinates.groupId == "org.codehaus.plexus" && coordinates.artifactId == "plexus"
        || coordinates.artifactId == "plexus-root")
        pomText.replace("&oslash;", "ø")
    else if (coordinates.artifactId == "hadoop-project")
        // Removing Xlint: prefix (that is recognized as an unknown namespace by XML parser, making entire XML invalid)
        pomText
            .replace("<Xlint:-", "<")
            .replace("<Xlint:", "<")
    else
        pomText


private val Parent.coordinates: MavenCoordinates
    get() = mavenCoordinatesTrimmed(groupId = groupId, artifactId = artifactId, version = version)

private fun Profile.isActiveByDefault(): Boolean = activation?.any { it.activeByDefault == true } == true

private fun List<Profile>.properties(): Properties? {
    var properties: Properties? = null
    forEach {
        properties += it.properties
    }
    return properties.takeIf { it?.properties?.isNotEmpty() == true }
}

private fun List<Profile>.dependencies(): Dependencies? {
    var dependencies: Dependencies? = null
    forEach {
        dependencies += it.dependencies
    }
    return dependencies.takeIf { it?.dependencies?.isNotEmpty() == true }
}

private fun List<Profile>.dependencyManagement(): DependencyManagement? {
    var dependencyManagement: DependencyManagement? = null
    forEach {
        dependencyManagement += it.dependencyManagement
    }
    return dependencyManagement.takeIf { it?.dependencies?.dependencies?.isNotEmpty() == true }
}

private suspend fun Project.activeProfiles(settings: Settings, withoutProfileProperties: Properties?): List<Profile> {
    val profiles = this.profiles?.profiles ?: return emptyList()
    // properties defined by the profiles themselves are not known yet,
    // the inherited ones are all that can be used for the interpolation of an activation file path
    val projectWithoutProfileProperties = copy(properties = withoutProfileProperties)

    val activatedProfiles =
        profiles.filter { it.isActivated(settings, projectWithoutProfileProperties) }.takeIf { it.isNotEmpty() }
            ?: profiles.filter { it.isActiveByDefault() }

    return activatedProfiles
}

/**
 * @param project the pom the profile belongs to, with the properties inherited from its parents;
 * its properties take part in the interpolation of an activation file path.
 */
internal suspend fun Profile.isActivated(settings: Settings, project: Project): Boolean {
    return activation != null && activation.isActivated(settings, project)
}

private suspend fun List<ProfileActivation>.isActivated(settings: Settings, project: Project) =
    isNotEmpty() && this.all { it.isActivated(settings, project) }

private suspend fun ProfileActivation.isActivated(settings: Settings, project: Project): Boolean {
    val dynamicInputs = getDynamicInputs()

    return !(jdk == null && os == null && property == null && file == null)
            && (jdk == null || jdk.isActiveJdk(settings, dynamicInputs))
            && (os == null || os.isActive(dynamicInputs))
            && (property == null || property.isActive(dynamicInputs))
            && (file == null || file.isActive(project, dynamicInputs))
}

private fun getAndRegisterSystemProperty(name: String, dynamicInputs: DynamicInputs): String? =
    dynamicInputs.readSystemProperty(name)

private fun String.isActiveJdk(settings: Settings, dynamicInputs: DynamicInputs): Boolean {
    val actualVersion = settings.jdkVersion?.value
        ?: getAndRegisterSystemProperty("java.version", dynamicInputs).takeIf { !it.isNullOrEmpty() }
        ?: return false

    val expectedJdk = this

    return if (expectedJdk.startsWith("!")) {
        !actualVersion.startsWith(expectedJdk.substring(1))
    } else if (expectedJdk.isRange()) {
        val range = expectedJdk.getRange() ?: return false
        actualVersion.isInRange(range)
        // "Failed to determine JDK activation for profile " + profile.getId() + " due invalid JDK version: '" + version + "'")
    } else {
        actualVersion.startsWith(expectedJdk)
    }
}

private fun ActivationOS.isActive(dynamicInputs: DynamicInputs): Boolean {
    if (name == null && family == null && arch == null && version == null)
        // all properties are omitted, there is nothing to check => condition is not met
        return false

    return OsActivationParameter.OS_NAME.matches(name, dynamicInputs)
            && OsActivationParameter.OS_FAMILY.matches(family, dynamicInputs)
            && OsActivationParameter.OS_ARCH.matches(arch, dynamicInputs)
            && OsActivationParameter.OS_VERSION.matches(version, dynamicInputs)
}

private enum class OsActivationParameter(
    /**
     * The value of parameter calculated based on the current system environment.
     */
    val value: String,
    /**
     * The system properties the actual [value] is derived from.
     *
     * They are registered as dynamic inputs whenever this parameter takes part in an activation condition, so that a
     * cached resolution is invalidated when the environment it was resolved in changes. Note that the architecture
     * alone doesn't identify a host: without registering these, a resolution cached on Linux x64 would be reused on
     * Windows x64, where different profiles are active.
     */
    val systemProperties: List<String>,
) {
    OS_NAME(value = Os.OS_NAME, systemProperties = ["os.name"]),

    /**
     * A host belongs to several OS families at once: macOS is both `mac` and `unix`, for instance.
     *
     * [Os.OS_FAMILY] is merely the first family of the host in an unspecified iteration order, so the expected family
     * must be tested with [Os.isFamily] rather than compared to it. Comparing to [Os.OS_FAMILY] fails to activate
     * `<family>unix</family>` profiles on macOS (where that first family is `mac`), while Maven activates them, see
     * `org.apache.maven.model.profile.activation.OperatingSystemProfileActivator`.
     */
    // Plexus derives the family from 'os.name', and additionally uses 'path.separator' to tell 'unix' and 'dos' apart
    OS_FAMILY(value = Os.OS_FAMILY, systemProperties = ["os.name", "path.separator"]) {
        override fun matchesActualValue(expectedValue: String): Boolean = Os.isFamily(expectedValue)
    },

    OS_ARCH(value = Os.OS_ARCH, systemProperties = ["os.arch"]),

    OS_VERSION(value = Os.OS_VERSION, systemProperties = ["os.version"]) {
        override fun matches(expectedRawValue: String?, dynamicInputs: DynamicInputs): Boolean {
            // If the expected value is not defined, => this condition matches without checking the actual value.
            if (expectedRawValue == null) return true

            registerSystemProperties(dynamicInputs)

            val actualVersion = value.lowercase()
            return if (expectedRawValue.startsWith("regex:")) {
                actualVersion.matches(expectedRawValue.substring(REGEX_PREFIX.length).toRegex())
            } else {
                super.matches(expectedRawValue, dynamicInputs)
            }
        }
    };

    open fun matches(expectedRawValue: String?, dynamicInputs: DynamicInputs): Boolean {
        // If the expected value is not defined, => this condition matches without checking the actual value.
        if (expectedRawValue == null) return true

        // Register system properties used for calculation the actual value of activation parameters as proceed
        registerSystemProperties(dynamicInputs)

        val expectedValueNegative = expectedRawValue.startsWith("!")
        val expectedValue = if (expectedValueNegative) expectedRawValue.substring(startIndex = 1) else expectedRawValue

        val valuesMatch = matchesActualValue(expectedValue)

        // XOR?
        return if (expectedValueNegative) {
            // condition is met if the property value is not defined or does not match
            !valuesMatch
        } else {
            // condition is met if the property value is defined and match
            valuesMatch
        }
    }

    /**
     * Whether the current system environment matches the [expectedValue] declared in the activation condition
     * (with the leading `!` of a negated condition already stripped).
     */
    open fun matchesActualValue(expectedValue: String): Boolean = value.equals(expectedValue, ignoreCase = true)

    /**
     * Registers the [systemProperties] this parameter is derived from as dynamic inputs of the resolution.
     */
    fun registerSystemProperties(dynamicInputs: DynamicInputs) {
        systemProperties.forEach { getAndRegisterSystemProperty(it, dynamicInputs) }
    }
}

private const val REGEX_PREFIX: String = "regex:"

/**
 * Checks the `<file>` profile activation condition the same way Maven does, see
 * `org.apache.maven.model.profile.activation.FileProfileActivator`.
 */
private fun ActivationFile.isActive(project: Project, dynamicInputs: DynamicInputs): Boolean {
    val [declaredPath, existing] = if (!exists.isNullOrBlank()) {
        exists to true
    } else if (!missing.isNullOrBlank()) {
        missing to false
    } else {
        // both conditions are omitted, there is no path to check => condition is not met
        return false
    }

    val actualPath = declaredPath
        // Maven declines interpolation of the path referencing the project directory if the latter is unknown,
        // and the pom of an external library has no project directory
        .takeIf { !it.contains($$"${basedir}") }
        ?.expandTemplate(project, dynamicInputs)
        // the path is presented but can't be parsed
        ?.toPath()
        // a relative path could only be resolved against the project directory, and an external library pom has none
        ?.takeIf { it.isAbsolute }
        ?: return false

    // Register path used in profile activation condition as proceed
    val actualPathExists = dynamicInputs.checkPathExistence(actualPath)

    return if (existing) actualPathExists else !actualPathExists
}

private fun ActivationProperty.isActive(dynamicInputs: DynamicInputs): Boolean {
    if (name.isNullOrBlank())
        // property name is omitted, there is nothing to check => condition is not met
        return false

    val propertyNameNegative = name.startsWith("!")
    val propertyName = if (propertyNameNegative) name.substring(startIndex = 1) else name

    // todo (AB) : Support special property 'packaging' that should be taken from the POM file of a children of this parent POM.
    val actualPropertyValue = dynamicInputs.systemPropertyOrEnvironmentVariable(propertyName)

    return if (propertyNameNegative) {
        // condition is met if the property is not defined
        actualPropertyValue == null
    } else {
        // property should be defined
        if (value.isNullOrBlank()) {
            // condition is met if the property is defined to any value
            actualPropertyValue != null
        } else {
            // value is defined
            val expectedValueNegative = value.startsWith("!")
            val expectedValue = if (expectedValueNegative) value.substring(startIndex = 1) else value
            if (expectedValueNegative) {
                // condition is met if the property is either not defined or its value is different from the expected value
                actualPropertyValue == null || actualPropertyValue != expectedValue
            } else {
                actualPropertyValue == expectedValue
            }
        }
    }
}

private fun String.toPath() = try {
    Path(this)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private fun String.isRange(): Boolean {
    return startsWith("[") || startsWith("(")
}

private fun String.isInRange(range: Range): Boolean {
    val actualJdkVersion = toJdkVersion() ?: return false // todo (AB) : Report warning

    val leftBoundMatches = if (range.left.closed)
        actualJdkVersion >= range.left.version
    else
        actualJdkVersion > range.left.version

    val rightBoundMatches = if (range.right.closed)
        actualJdkVersion <= range.right.version
    else
        actualJdkVersion < range.right.version

    return leftBoundMatches && rightBoundMatches
}

private fun String.getRangeBoundVersionParts() = split(".").toList()

private val filterRedundantSymbols: Pattern = Pattern.compile("[^\\d._-]")
private val filterDelimiter: Pattern = Pattern.compile("[._-]")

private fun String.toJdkVersion(): JdkVersion? =
    JdkVersion.create(
        filterRedundantSymbols.matcher(this).replaceAll("").split(filterDelimiter)
    )

private fun String.getRange(): Range? {
    val rangeParts = this.split(",")
    if (rangeParts.size > 2) return null // todo (AB) : Report warning

    val leftBound = rangeParts[0].trim().let {
        val closed = it.startsWith("[")
        val value = if (it.startsWith("[") || it.startsWith("(")) it.substring(startIndex = 1) else it
        val version = if (value.isBlank()) JdkVersion.minJdkVersion else JdkVersion.create(value.getRangeBoundVersionParts())
            ?: return null // todo (AB) : Report warning
        RangeBound(version, closed)
    }
    val rightBound = if (rangeParts.size == 2) {
        val rightPart = rangeParts[1].trim()
        val closed = rightPart.endsWith("]")
        val value = if (rightPart.endsWith("]") || rightPart.endsWith(")"))
            rightPart.substring(startIndex = 0, endIndex = rightPart.length - 1)
        else
            rightPart
        val version = if (value.isBlank()) JdkVersion.maxJdkVersion else JdkVersion.create(value.getRangeBoundVersionParts())
            ?: return null // todo (AB) : Report warning
        RangeBound(version, closed)
    } else
        RangeBound(JdkVersion.maxJdkVersion, false)

    return Range(leftBound, rightBound)
}

private class Range(val left: RangeBound, val right: RangeBound) {
    override fun toString(): String {
        return "${if(left.closed) "[" else "("}${left.version},${right.version}${if (right.closed) "]" else ")"}"
    }
}

private data class JdkVersion private constructor(
    val parts: List<Int>
): Comparable<JdkVersion> {
    override fun compareTo(other: JdkVersion): Int {
        for (i in 0..< Math.max(parts.size, other.parts.size)) {
            val thisPart = parts.getOrElse(i) { Int.MIN_VALUE }
            val otherPart = other.parts.getOrElse(i) { Int.MIN_VALUE }
            if (thisPart != otherPart) return thisPart.compareTo(otherPart)
        }
        return 0
    }

    companion object {
        fun create(parts: List<String>): JdkVersion?{
            if (parts.isEmpty()) error("Version parts should not be empty")
            val versionParts = parts.map { it.toIntOrNull() ?: return null } // todo (AB) : Report warning
            return JdkVersion(versionParts)
        }

        val minJdkVersion = JdkVersion(listOf(Int.MIN_VALUE))
        val maxJdkVersion = JdkVersion(listOf(Int.MAX_VALUE))
    }

    override fun toString(): String {
        return parts.joinToString(".")
    }
}

private data class RangeBound(
    val version: JdkVersion,
    val closed: Boolean
)
