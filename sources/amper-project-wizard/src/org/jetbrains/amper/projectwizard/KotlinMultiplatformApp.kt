/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import java.nio.file.Path

/**
 * A parameterized Kotlin Multiplatform application to generate (see [KotlinMultiplatformProjectGenerator]), once all
 * the required input has been gathered (either from CLI flags or interactive prompts).
 */
data class KotlinMultiplatformApp(
    val targets: Set<KotlinMultiplatformTargetPlatform>,
    val projectId: String,
    val projectName: String,
)

/**
 * All the files to generate for a [KotlinMultiplatformApp], ready to be checked for conflicts and extracted.
 */
class ProjectExtractionSchema internal constructor(internal val files: List<GeneratedFile>) {
    val relativePaths: List<String> get() = files.map { it.relativePath }

    fun extractTo(outputDir: Path) = files.forEach { it.extractTo(outputDir) }
}

/**
 * Resolves all the files to generate for this app.
 *
 * @param generateGitFiles whether to add `.gitignore` and `.gitattributes`
 * @param generateAiFiles whether to keep the files that configure AI agents
 */
fun KotlinMultiplatformApp.toExtractionSchema(
    generateGitFiles: Boolean = true,
    generateAiFiles: Boolean = true,
): ProjectExtractionSchema =
    ProjectExtractionSchema(files = buildList {
        addAll(
            KotlinMultiplatformProjectGenerator.generate(targets, projectId, projectName)
                .filter { generateAiFiles || it.relativePath !in AiAgentFilePaths }
        )
        if (generateGitFiles) {
            add(TextGeneratedFile(relativePath = ".gitignore", content = KotlinMultiplatformGitIgnore))
            add(generateGitAttributesFile())
        }
    })

/** The paths of the files that only configure AI agents. */
private val AiAgentFilePaths = setOf(".mcp.json")

private fun generateGitAttributesFile(): TextGeneratedFile {
    val hasPngFiles = KotlinMultiplatformProjectGenerator.template.listFiles().any { it.relativePath.endsWith(".png") }
    return TextGeneratedFile(relativePath = ".gitattributes", content = gitAttributes(hasPngFiles))
}

internal const val KotlinMultiplatformGitIgnore = """
    /build/
    .idea/
    **/module.xcodeproj/*
    !**/module.xcodeproj/project.pbxproj
    !**/module.xcodeproj/xcshareddata/
    !**/module.xcodeproj/project.xcworkspace/
    **/xcshareddata/WorkspaceSettings.xcsettings
    xcuserdata
""".trimIndent() + "\n"

/**
 * The content of the `.gitattributes` file of a generated project, depending on whether it contains PNG files.
 */
fun gitAttributes(hasPngFiles: Boolean): String = buildString {
    append("* text=auto\n\n")
    append("*.sh text eol=lf\n")
    append("*.bat text eol=crlf\n")
    if (hasPngFiles) append("\n*.png binary\n")
    append("\n# Kotlin CLI wrapper without extension\n")
    append("kotlin text eol=lf\n")
}
