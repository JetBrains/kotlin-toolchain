/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates
import org.jetbrains.amper.templates.TemplateFile
import java.nio.file.Path

/**
 * The two ways `kotlin new`/`kotlin init` can generate a project, once all the required input has been gathered
 * (either from CLI flags or interactive prompts).
 */
sealed interface NewProjectMode {
    /** Generate the project from the given [template]. */
    data class FromTemplate(val template: AmperProjectTemplate, val projectName: String) : NewProjectMode

    /** Generate a parameterized Compose Multiplatform application (see [ComposeMultiplatformProjectGenerator]). */
    data class ComposeMultiplatformApp(
        val targets: Set<ComposeMultiplatformTargetPlatform>,
        val projectId: String,
        val projectName: String,
    ) : NewProjectMode
}

/**
 * A single file to generate, from either of the [NewProjectMode]s: it's either copied verbatim from the template
 * resources ([TemplateFile], possibly binary), or generated in memory ([TextGeneratedFile] or [BinaryGeneratedFile]).
 */
internal interface ExtractableFile {
    /** The path of this file relative to the project root. */
    val relativePath: String

    /** Writes this file under [outputDir], overwriting it if it already exists. */
    fun extractTo(outputDir: Path)
}

/**
 * All the files to generate for a resolved [NewProjectMode], ready to be checked for conflicts and extracted.
 */
class ProjectExtractionSchema internal constructor(internal val files: List<ExtractableFile>) {
    val relativePaths: List<String> get() = files.map { it.relativePath }

    fun extractTo(outputDir: Path) = files.forEach { it.extractTo(outputDir) }
}

/**
 * A short, human-readable description of what this mode generates, for progress/log messages.
 */
fun NewProjectMode.describe(highlight: (String) -> String): String = when (this) {
    is NewProjectMode.FromTemplate -> "template ${highlight(template.id)}"
    is NewProjectMode.ComposeMultiplatformApp ->
        "a Compose Multiplatform application (${highlight(targets.map { it.cliValue }.sorted().joinToString())})"
}

fun NewProjectMode.toExtractionSchema(generateGitFiles: Boolean = true): ProjectExtractionSchema {
    val sourceTemplate = sourceTemplate()
    return ProjectExtractionSchema(files = buildList {
        addAll(projectFiles(sourceTemplate))
        if (generateGitFiles) {
            add(sourceTemplate.generateGitIgnoreFile())
            add(sourceTemplate.generateGitAttributesFile())
        }
    })
}

private fun NewProjectMode.projectFiles(sourceTemplate: AmperProjectTemplate): List<ExtractableFile> = when (this) {
    is NewProjectMode.FromTemplate -> sourceTemplate.listFiles().map { it.asExtractableFile(projectName) }
    is NewProjectMode.ComposeMultiplatformApp -> ComposeMultiplatformProjectGenerator.generate(
        targets = targets,
        projectId = projectId,
        projectName = projectName,
    )
}

private fun NewProjectMode.sourceTemplate(): AmperProjectTemplate = when (this) {
    is NewProjectMode.FromTemplate -> template
    is NewProjectMode.ComposeMultiplatformApp -> AmperProjectTemplates.availableTemplates.singleOrNull {
        it.id == ComposeMultiplatformTemplateId
    } ?: error("Template '$ComposeMultiplatformTemplateId' not found in the available project templates")
}

private fun AmperProjectTemplate.generateGitIgnoreFile(): TextGeneratedFile {
    val content = if (id == ComposeMultiplatformTemplateId) ComposeMultiplatformGitIgnore else DefaultGitIgnore
    return TextGeneratedFile(relativePath = ".gitignore", content = content)
}

private fun AmperProjectTemplate.generateGitAttributesFile(): TextGeneratedFile {
    val hasPngFiles = listFiles().any { it.relativePath.endsWith(".png") }
    return TextGeneratedFile(
        relativePath = ".gitattributes",
        content = gitAttributes(hasPngFiles),
    )
}

internal val DefaultGitIgnore = """
    /build/
    .idea/
""".trimIndent() + "\n"

internal const val ComposeMultiplatformGitIgnore = """
    /build/
    .idea/
    **/module.xcodeproj/*
    !**/module.xcodeproj/project.pbxproj
    !**/module.xcodeproj/xcshareddata/
    !**/module.xcodeproj/project.xcworkspace/
    **/xcshareddata/WorkspaceSettings.xcsettings
    xcuserdata
""".trimIndent() + "\n"

private fun gitAttributes(hasPngFiles: Boolean): String = buildString {
    append("* text=auto\n\n")
    append("*.sh text eol=lf\n")
    append("*.bat text eol=crlf\n")
    if (hasPngFiles) append("\n*.png binary\n")
    append("\n# Kotlin CLI wrapper without extension\n")
    append("kotlin text eol=lf\n")
}

/**
 * Adapts a resource-backed [TemplateFile] to [ExtractableFile], so both project modes produce a uniform file list.
 */
private fun TemplateFile.asExtractableFile(projectName: String): ExtractableFile = object : ExtractableFile {
    override val relativePath: String get() = this@asExtractableFile.relativePath
    override fun extractTo(outputDir: Path) = this@asExtractableFile.extractTo(
        projectRoot = outputDir,
        projectId = DefaultProjectId,
        projectName = projectName,
    )
}
