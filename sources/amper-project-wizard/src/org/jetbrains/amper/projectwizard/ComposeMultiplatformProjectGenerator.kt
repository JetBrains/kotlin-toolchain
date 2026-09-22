/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates
import org.jetbrains.amper.templates.AndroidAppModule
import org.jetbrains.amper.templates.AppDir
import org.jetbrains.amper.templates.CoreModule
import org.jetbrains.amper.templates.DesktopAppModule
import org.jetbrains.amper.templates.IosAppModule
import org.jetbrains.amper.templates.ServerModule
import org.jetbrains.amper.templates.SharedModule
import org.jetbrains.amper.templates.TemplateFile
import org.jetbrains.amper.templates.TemplatePlaceholder
import org.jetbrains.amper.templates.TemplatePlaceholders
import org.jetbrains.amper.templates.WebAppModule
import org.jetbrains.amper.templates.isBinaryTemplateFile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeLines

/** The project id used by the wizard when the user doesn't provide one. */
const val DefaultProjectId = "org.example.project"

/** The id of the `compose-multiplatform` project template in [AmperProjectTemplates.availableTemplates]. */
internal const val ComposeMultiplatformTemplateId = "compose-multiplatform"

private const val DerivedProjectIdPrefix = "org.example."

private const val CoreModuleDir = CoreModule
private const val SharedModuleDir = "$AppDir$SharedModule"

/**
 * A generated project file, ready to be written to disk with [extractTo].
 */
internal sealed interface GeneratedFile : ExtractableFile

internal data class BinaryGeneratedFile(
    override val relativePath: String,
    val content: ByteArray,
) : GeneratedFile {
    override fun extractTo(outputDir: Path) {
        val path = outputDir.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeBytes(content)
    }
}

internal data class TextGeneratedFile(
    override val relativePath: String,
    val content: String,
) : GeneratedFile {
    override fun extractTo(outputDir: Path) {
        val path = outputDir.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeLines(content.reader().readLines())
    }
}

/**
 * Generates the files for a parameterized Compose Multiplatform application, by pruning and rewriting the
 * `compose-multiplatform` project template (see the [ComposeMultiplatformTargetPlatform] docs for target semantics):
 *
 *  - only the modules for the selected [targets] are kept,
 *  - the Compose `shared` module is kept when at least one UI target is selected,
 *  - a `core` module shared by the client apps and Ktor backend is kept when the server target is selected,
 *  - `project.yaml`'s `modules:` list is rewritten to only reference the kept modules,
 *  - KMP module `platforms:` lists are rewritten to only reference the platforms of the selected targets,
 *  - the `app/` directory is flattened when no server target is selected,
 *  - the [TemplatePlaceholders] are substituted in text files, with the project id and the escaped project name,
 *  - `.gitignore` and `.gitattributes` are added by [NewProjectMode.toExtractionSchema].
 */
internal object ComposeMultiplatformProjectGenerator {

    private val template: AmperProjectTemplate
        get() = AmperProjectTemplates.availableTemplates.singleOrNull { it.id == ComposeMultiplatformTemplateId }
            ?: error("Template '$ComposeMultiplatformTemplateId' not found in the available project templates")

    fun generate(
        targets: Set<ComposeMultiplatformTargetPlatform>,
        projectId: String,
        projectName: String,
    ): List<GeneratedFile> {
        checkValidProjectId(projectId)
        require(targets.isNotEmpty()) {
            "At least one target is required to generate a Compose Multiplatform project"
        }

        val serverIncluded = ComposeMultiplatformTargetPlatform.SERVER in targets
        val sharedModuleTargets = targets.filter { it.sharedModuleSourceSetQualifier != null }
        val keptModuleDirs = buildSet {
            addAll(targets.map { it.moduleDir })
            if (sharedModuleTargets.isNotEmpty()) add(SharedModuleDir)
            if (serverIncluded) add(CoreModuleDir)
        }
        val keptSourceSetQualifiers = sharedModuleTargets.mapNotNull { it.sharedModuleSourceSetQualifier }.toSet()
        val sharedModulePlatforms = sharedModuleTargets.flatMap { it.sharedModulePlatforms }
        val coreModulePlatforms = targets.flatMap { it.coreModulePlatforms }

        return template.listFiles()
            .filter { it.isKeptProjectFile(keptModuleDirs) }
            .filter { it.matchesKeptSourceSets(keptSourceSetQualifiers) }
            .map { it.toGeneratedFile() }
            .map { it.rewriteIfProjectYaml(keptModuleDirs, flattenAppDirectory = !serverIncluded) }
            .map { it.rewriteIfReadme(targets, keptModuleDirs) }
            .map { it.rewritePlatformsIfModuleYaml(SharedModuleDir, sharedModulePlatforms) }
            .map { it.rewritePlatformsIfModuleYaml(CoreModuleDir, coreModulePlatforms) }
            .map { it.substitutePlaceholders(projectId, projectName, flattenAppDirectory = !serverIncluded) }
            .map { it.removeCoreUsageUnlessServerIsIncluded(serverIncluded) }
            .map { it.flattenPathUnlessServerIncluded(serverIncluded) }
    }

    private fun TemplateFile.isKeptProjectFile(keptModuleDirs: Set<String>): Boolean {
        return '/' !in relativePath || keptModuleDirs.any { relativePath.startsWith("$it/") }
    }

    /**
     * Whether all `@platform`-qualified source-set segments in this file's path (e.g. `src@android`, `test@jvm`)
     * correspond to a selected target. Files with no `@platform` qualifier (common source sets) always match.
     */
    private fun TemplateFile.matchesKeptSourceSets(keptSourceSetQualifiers: Set<String>): Boolean =
        relativePath.split('/')
            .mapNotNull { segment -> segment.substringAfter('@', missingDelimiterValue = "").ifEmpty { null } }
            .all { qualifier -> qualifier in keptSourceSetQualifiers }

    private fun TemplateFile.toGeneratedFile(): GeneratedFile = if (isBinaryTemplateFile(relativePath)) {
        BinaryGeneratedFile(relativePath = relativePath, content = resourceUrl.openStream().use { it.readBytes() })
    } else {
        TextGeneratedFile(
            relativePath = relativePath,
            content = resourceUrl.openStream().bufferedReader().use { it.readText() },
        )
    }

    private fun GeneratedFile.rewriteIfProjectYaml(
        keptModuleDirs: Set<String>,
        flattenAppDirectory: Boolean,
    ): GeneratedFile {
        if (relativePath != "project.yaml") return this
        val modulesBlock = keptModuleDirs.sorted().joinToString(separator = "\n") { moduleDir ->
            val generatedModuleDir = if (flattenAppDirectory) moduleDir.removePrefix(AppDir) else moduleDir
            "  - $generatedModuleDir"
        }
        return transformText { ModulesListRegex.replaceFirstMatch(it, "modules:\n$modulesBlock\n") }
    }

    private fun GeneratedFile.rewriteIfReadme(
        targets: Set<ComposeMultiplatformTargetPlatform>,
        keptModuleDirs: Set<String>,
    ): GeneratedFile {
        if (relativePath != "README.md") return this
        return transformText { readme ->
            val targetNames = ComposeMultiplatformTargetPlatform.entries
                .filter { it in targets }
                .joinToString { it.displayName }
            ReadmeSectionsByModule.entries
                .filter { it.key !in keptModuleDirs }
                .flatMap { it.value }
                .fold(ReadmeTargetsRegex.replaceFirst(readme, "targeting $targetNames, built with")) {
                    content, sectionStart -> content.removeReadmeBullet(sectionStart)
                }
        }
    }

    private fun GeneratedFile.rewritePlatformsIfModuleYaml(
        moduleDir: String,
        platforms: List<String>,
    ): GeneratedFile {
        if (relativePath != "$moduleDir/module.yaml") return this
        val newPlatformsDeclaration = "platforms: [${platforms.distinct().joinToString(separator = ", ")}]"
        return transformText { PlatformsListRegex.replaceFirstMatch(it, newPlatformsDeclaration) }
    }

    private fun GeneratedFile.removeCoreUsageUnlessServerIsIncluded(serverIncluded: Boolean): GeneratedFile {
        if (serverIncluded) return this
        return when (relativePath) {
            "$SharedModuleDir/module.yaml" -> transformText { CoreDependencyRegex.replace(it, "") }
            "$SharedModuleDir/src/Greeting.kt" -> transformText {
                it.replace(
                    "        return sayHello(platform.name)",
                    "        return \"Hello, \${platform.name}!\"",
                )
            }
            else -> this
        }
    }

    private fun GeneratedFile.substitutePlaceholders(
        projectId: String,
        projectName: String,
        flattenAppDirectory: Boolean,
    ): GeneratedFile = transformText {
        TemplatePlaceholders.substituteIn(it, relativePath, projectId, projectName, flattenAppDirectory)
    }

    private fun GeneratedFile.flattenPathUnlessServerIncluded(serverIncluded: Boolean): GeneratedFile {
        if (serverIncluded) return this
        val flattenedPath = relativePath.removePrefix(AppDir)
        return when (this) {
            is BinaryGeneratedFile -> copy(relativePath = flattenedPath)
            is TextGeneratedFile -> copy(relativePath = flattenedPath)
        }
    }

    private fun GeneratedFile.transformText(transform: (String) -> String): GeneratedFile = when (this) {
        is BinaryGeneratedFile -> this
        is TextGeneratedFile -> copy(content = transform(content))
    }

    private fun String.removeReadmeBullet(sectionStart: String): String {
        val lines = split('\n').toMutableList()
        val firstLine = lines.indexOfFirst { it.startsWith(sectionStart) }
        check(firstLine >= 0) { "README section '$sectionStart' not found" }
        var lineAfterSection = firstLine + 1
        while (lineAfterSection < lines.size && lines[lineAfterSection].startsWith("  ")) {
            lineAfterSection++
        }
        lines.subList(firstLine, lineAfterSection).clear()
        return lines.joinToString("\n")
    }

    private val ReadmeSectionsByModule = mapOf(
        "$AppDir$AndroidAppModule" to [readmeModuleLink(TemplatePlaceholder.AndroidAppModuleName), "- Android app:"],
        "$AppDir$DesktopAppModule" to [readmeModuleLink(TemplatePlaceholder.DesktopAppModuleName), "- Desktop app:"],
        "$AppDir$IosAppModule" to [readmeModuleLink(TemplatePlaceholder.IosAppModuleName), "- iOS app:"],
        SharedModuleDir to [readmeModuleLink(TemplatePlaceholder.SharedModuleName)],
        "$AppDir$WebAppModule" to [readmeModuleLink(TemplatePlaceholder.WebAppModuleName), "- Web app:"],
        CoreModuleDir to [readmeModuleLink(TemplatePlaceholder.CoreModuleName, isAppModule = false)],
        ServerModule to [readmeModuleLink(TemplatePlaceholder.ServerModuleName, isAppModule = false), "- Server:"],
    )

    private fun readmeModuleLink(placeholder: TemplatePlaceholder, isAppModule: Boolean = true): String {
        val appDir = if (isAppModule) TemplatePlaceholder.AppModuleDir.text else ""
        return "- [/$appDir${placeholder.text}]"
    }

    private val ModulesListRegex = Regex("""modules:\r?\n(?:\s*- .+(?:\r?\n)?)+""")
    private val CoreDependencyRegex = Regex("""(?m)^  - //$CoreModuleDir\r?\n""")
    private val PlatformsListRegex = Regex("""platforms: \[[^]]*]""")
    private val ReadmeTargetsRegex = Regex("""targeting .+, built with""")

    private fun Regex.replaceFirstMatch(input: String, replacement: String): String {
        val match = find(input) ?: error("Pattern '$pattern' not found in:\n$input")
        return input.replaceRange(match.range, replacement)
    }
}

class InvalidProjectIdException(message: String) : IllegalArgumentException(message)

/**
 * Checks the wizard's conservative common contract for Kotlin packages, Android application IDs/namespaces, and iOS
 * bundle IDs. Lowercase-only is an intentional wizard policy. This validation does not guarantee uniqueness,
 * domain ownership, or store availability.
 *
 * @throws InvalidProjectIdException if [projectId] does not satisfy this contract.
 */
fun checkValidProjectId(projectId: String) {
    val invalidReason = invalidProjectIdReason(projectId) ?: return
    throw InvalidProjectIdException("Invalid project id '$projectId': $invalidReason.")
}

private fun invalidProjectIdReason(projectId: String): String? {
    if (projectId.isEmpty()) return "the package name must not be empty"

    val segments = projectId.split('.')
    if (segments.size < 2) return "the package name must have at least 2 segments separated with a dot"
    if (segments.any { it.isEmpty() }) return "package name segments must not be empty"
    if (segments.any { segment -> segment.any { it !in 'a'..'z' && it !in '0'..'9' } }) {
        return "package name segments must contain only lowercase ASCII letters and digits"
    }
    if (segments.any { it.first() !in 'a'..'z' }) {
        return "each package name segment must start with a letter"
    }

    val rootSegment = segments.first()
    if (rootSegment in ReservedProjectIdRoots) {
        return "'$rootSegment' cannot be used as the first package name segment"
    }

    val forbiddenSegment = segments.firstOrNull { it in ForbiddenProjectIdSegments }
    if (forbiddenSegment != null) {
        return "'$forbiddenSegment' is a reserved keyword and cannot be used as a package name segment"
    }
    return null
}

/** Derives a valid project id from a display name. */
fun defaultProjectId(projectName: String): String {
    val normalizedSegment = projectName.lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .ifEmpty { "project" }
    val repairedSegment = when {
        normalizedSegment.first() in '0'..'9' -> "project$normalizedSegment"
        normalizedSegment in ForbiddenProjectIdSegments -> "project$normalizedSegment"
        else -> normalizedSegment
    }
    val projectId = "$DerivedProjectIdPrefix$repairedSegment"
    checkValidProjectId(projectId)
    return projectId
}

private val ReservedProjectIdRoots: Set<String> = ["java", "kotlin"]

private val ForbiddenProjectIdSegments: Set<String> = [
    // Kotlin hard keywords and reserved literals.
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while",
    // Java reserved keywords are excluded so users can add Java interoperability later.
    // Contextual keywords such as data/module/record remain valid.
    "abstract", "assert", "boolean", "byte", "case", "catch", "char", "const", "default", "double", "enum",
    "extends", "final", "finally", "float", "goto", "implements", "import", "instanceof", "int", "long",
    "native", "new", "private", "protected", "public", "short", "static", "strictfp", "switch", "synchronized",
    "throws", "transient", "void", "volatile",
]
