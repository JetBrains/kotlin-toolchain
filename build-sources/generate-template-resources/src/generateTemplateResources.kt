/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.templates.json.TemplateDescriptor
import org.jetbrains.amper.templates.json.TemplateResource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

@TaskAction
@OptIn(ExperimentalSerializationApi::class)
fun generateTemplateResources(
    @Output outputDir: Path,
    @Input templatesDir: Path,
) {
    outputDir.createDirectories()
    val templateDirs = templatesDir.listDirectoryEntries().sortedBy { it.name }
    val descriptors = templateDirs.map { prepareTemplateResources(it, outputDir) }

    (outputDir / "templates/templates.json")
        .createParentDirectories()
        .writeTextIfChanged(Json.encodeToString(descriptors)) // important for caching of dependent modules
}

private fun prepareTemplateResources(templateDir: Path, outputDir: Path): TemplateDescriptor {
    val templateId = templateDir.name
    val storedPaths = templateDir.listRelativeFilePaths()
    return TemplateDescriptor(
        id = templateId,
        resources = storedPaths.map { relativePath ->
            TemplateResource("/templates/$templateId/$relativePath", relativePath)
        },
    )
}

private fun Path.listRelativeFilePaths(): List<String> =
    walk().filter { it.isRegularFile() }.map { it.relativeTo(this).joinToString("/") }.toList()

private fun Path.writeTextIfChanged(content: String) {
    if (exists()) {
        val existingContent = readText()
        if (content == existingContent) return
    }
    writeText(content)
}
