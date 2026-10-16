/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import org.jetbrains.amper.projectwizard.AgentsMdPath
import org.jetbrains.amper.projectwizard.KotlinMultiplatformApp
import org.jetbrains.amper.projectwizard.KotlinMultiplatformTargetPlatform
import org.jetbrains.amper.projectwizard.agentsMd
import org.jetbrains.amper.projectwizard.gitAttributes
import org.jetbrains.amper.projectwizard.toExtractionSchema
import org.jetbrains.amper.templates.AmperProjectTemplate
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeLines

/** The project id suggested in the help of the `kotlin new` and `kotlin init` commands. */
internal const val DefaultProjectId = "org.example.project"

private const val DefaultGitIgnore = "/build/\n.idea/\n"

/**
 * The two ways `kotlin new`/`kotlin init` can generate a project, once all the required input has been gathered
 * (either from CLI flags or interactive prompts).
 */
internal sealed interface NewProjectMode {
    /** Generate the project by copying the files of the given static [template] as is. */
    data class FromTemplate(val template: AmperProjectTemplate) : NewProjectMode

    /** Generate a parameterized Kotlin Multiplatform application with the project wizard. */
    data class KotlinMultiplatformApp(
        val targets: Set<KotlinMultiplatformTargetPlatform>,
        val projectId: String,
        val projectName: String,
    ) : NewProjectMode
}

/**
 * All the files to generate for a [NewProjectMode], ready to be checked for conflicts and extracted.
 */
internal class ProjectFiles(
    val relativePaths: List<String>,
    private val extract: (outputDir: Path) -> Unit,
) {
    fun extractTo(outputDir: Path) = extract(outputDir)
}

/**
 * A short, human-readable description of what this mode generates, for progress/log messages.
 */
internal fun NewProjectMode.describe(highlight: (String) -> String): String = when (this) {
    is NewProjectMode.FromTemplate -> "template ${highlight(template.id)}"
    is NewProjectMode.KotlinMultiplatformApp -> {
        val targets = targets.map { it.cliValue }.sorted().joinToString()
        "a Kotlin Multiplatform application (${highlight(targets)})"
    }
}

/**
 * Resolves all the files to generate for this mode.
 *
 * @param generateGitFiles whether to add `.gitignore` and `.gitattributes`
 * @param generateAiFiles whether to add the `AGENTS.md` file for AI agents
 */
internal fun NewProjectMode.toExtractionSchema(generateGitFiles: Boolean, generateAiFiles: Boolean): ProjectFiles =
    when (this) {
        is NewProjectMode.FromTemplate -> template.toExtractionSchema(generateGitFiles, generateAiFiles)
        is NewProjectMode.KotlinMultiplatformApp -> {
            val schema = KotlinMultiplatformApp(targets, projectId, projectName)
                .toExtractionSchema(generateGitFiles, generateAiFiles)
            ProjectFiles(schema.relativePaths, schema::extractTo)
        }
    }

private fun AmperProjectTemplate.toExtractionSchema(generateGitFiles: Boolean, generateAiFiles: Boolean): ProjectFiles {
    val files = listFiles()
    val generatedFiles = buildList {
        if (generateGitFiles) addAll(templateGitFiles(hasPngFiles = files.any { it.relativePath.endsWith(".png") }))
        if (generateAiFiles) {
            add(TextFile(AgentsMdPath, agentsMd(hotReloadable = id in HotReloadableTemplateIds)))
        }
    }
    return ProjectFiles(
        relativePaths = files.map { it.relativePath } + generatedFiles.map { it.relativePath },
        extract = { outputDir ->
            files.forEach { it.copyTo(outputDir) }
            generatedFiles.forEach { it.writeTo(outputDir) }
        },
    )
}

/** The ids of the static templates that contain an app supporting Compose Hot Reload. */
private val HotReloadableTemplateIds = setOf("compose-desktop")

private class TextFile(val relativePath: String, val content: String) {
    fun writeTo(outputDir: Path) {
        val path = outputDir.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeLines(content.reader().readLines())
    }
}

private fun templateGitFiles(hasPngFiles: Boolean): List<TextFile> = [
    TextFile(".gitignore", DefaultGitIgnore),
    TextFile(".gitattributes", gitAttributes(hasPngFiles)),
]
