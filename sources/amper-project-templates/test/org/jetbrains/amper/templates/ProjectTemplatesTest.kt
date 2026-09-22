/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.templates

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TestProjectId = "org.example.project"

class ProjectTemplatesTest {

    @Test
    fun `all templates are listed and can be instantiated`() {
        val templatesDirChildren = Path("resources/templates").listDirectoryEntries().map { it.name }.toSet()
        assertEquals(templatesDirChildren, AmperProjectTemplates.availableTemplates.map { it.id }.toSet())
    }

    @Test
    fun `IDE templates have expected ids and names`() {
        val expectedTemplates = mapOf(
            "compose-android" to "Android Application (Compose Multiplatform)",
            "compose-desktop" to "JVM GUI application (Compose Multiplatform)",
            "compose-multiplatform" to "Kotlin Multiplatform Application (Compose Multiplatform)",
            "jvm-cli" to "JVM console application",
            "kmp-lib" to "Kotlin Multiplatform library",
            "ktor-server" to "Ktor server application",
            "spring-boot-kotlin" to "Spring Boot application (Kotlin)",
        )

        assertEquals(expectedTemplates, AmperProjectTemplates.availableTemplates.associate { it.id to it.name })
    }

    @Test
    fun `templates ignore IDEA metadata without generating an IDEA directory`() {
        AmperProjectTemplates.availableTemplates.forEach { template ->
            assertFalse(
                template.listFiles().any { it.relativePath == ".idea/.gitignore" },
                "Template '${template.id}' must not generate an IDEA-specific .gitignore.",
            )
            withTempDir { outputDir ->
                template.extractTo(outputDir, projectName = "Sample App")

                assertFalse(
                    (outputDir / ".idea").exists(),
                    "Template '${template.id}' must not create an .idea directory.",
                )
            }
        }
    }

    @Test
    fun `Compose template uses placeholders for module names`() {
        val projectYaml = Path("resources/templates/compose-multiplatform/project.yaml").readText()
        TemplatePlaceholder.entries.filter { it.fixedValue != null }.forEach { placeholder ->
            assertContains(projectYaml, placeholder.text)
        }
    }

    @Test
    fun `template resources use placeholders instead of hardcoded project ids and names`() {
        val templatesDir = Path("resources/templates")
        val hardcodedValues = templatesDir.walk()
            .filter { it.isRegularFile() && !it.isBinary() }
            .filter { file ->
                val text = file.readText()
                TestProjectId in text || "composeApp" in text
            }
            .map { templatesDir.relativize(it).joinToString("/") }
            .toList()

        assertEquals(
            emptyList(),
            hardcodedValues,
            "These template files hardcode '$TestProjectId' or 'composeApp' instead of " +
                    "the '${TemplatePlaceholder.ProjectId.text}' and '${TemplatePlaceholder.ProjectName.text}' placeholders",
        )
    }

    @Test
    fun `extraction substitutes the given project id and name in every file`() = withTempDir { outputDir ->
        composeMultiplatformTemplate.listFiles().forEach {
            it.extractTo(outputDir, projectId = TestProjectId, projectName = "My App")
        }

        assertContains((outputDir / "app/shared/src/App.kt").readText(), "package $TestProjectId")
        assertContains(
            (outputDir / "app/androidApp/module.yaml").readText(),
            "namespace: $TestProjectId",
        )
        assertContains(
            (outputDir / "app/androidApp/res/values/strings.xml").readText(),
            ">My App</string>",
        )
        assertNoPlaceholdersLeftIn(outputDir)
    }

    @Test
    fun `extraction substitutes the given project id and name`() = withTempDir { outputDir ->
        composeMultiplatformTemplate.extractTo(outputDir, projectId = "com.acme.demo", projectName = "My App")

        assertContains((outputDir / "app/shared/src/App.kt").readText(), "package com.acme.demo")
        assertContains((outputDir / "server/src/Application.kt").readText(), "package com.acme.demo.server")
        assertContains((outputDir / "app/androidApp/module.yaml").readText(), "applicationId: com.acme.demo")
        assertContains((outputDir / "app/desktopApp/src/main.kt").readText(), "title = \"My App\"")
        assertNoPlaceholdersLeftIn(outputDir)
    }

    @Test
    fun `extraction escapes the project name for each text format`() = withTempDir { outputDir ->
        composeMultiplatformTemplate.extractTo(outputDir, projectName = "Bob's & <App>")

        assertContains(
            (outputDir / "app/androidApp/res/values/strings.xml").readText(),
            ">Bob\\'s &amp; &lt;App&gt;</string>",
        )
        assertContains(
            (outputDir / "app/webApp/resources/index.html").readText(),
            "<title>Bob&apos;s &amp; &lt;App&gt;</title>",
        )
        assertContains((outputDir / "app/desktopApp/src/main.kt").readText(), """title = "Bob's & <App>"""")
    }

    @Test
    fun `extraction copies every binary file unchanged and substitutes text placeholders`() {
        for (template in AmperProjectTemplates.availableTemplates) {
            withTempDir { outputDir ->
                template.extractTo(outputDir, projectId = "com.acme.demo", projectName = "My App")

                for (file in template.listFiles()) {
                    val bytes = file.resourceUrl.openStream().use { it.readBytes() }
                    if (bytes.isBinary()) {
                        assertContentEquals(
                            bytes,
                            (outputDir / file.relativePath).readBytes(),
                            "Binary file changed during extraction: '${template.id}/${file.relativePath}'",
                        )
                    }
                }
                assertNoPlaceholdersLeftIn(outputDir)
            }
        }
    }

    @Test
    fun `text extraction uses platform line endings without adding a blank line`() = withTempDir { tempDir ->
        val source = tempDir.resolve("source.txt")
        source.writeText("${TemplatePlaceholder.ProjectId.text}\r\n\r\ncafé\r\n")
        val file = TemplateFile(source.toUri().toURL(), "nested/file.txt")
        val outputDir = tempDir.resolve("output")

        file.extractTo(outputDir, projectId = TestProjectId, projectName = "My App")

        val expected = [TestProjectId, "", "café"].joinToString(
            separator = System.lineSeparator(),
            postfix = System.lineSeparator(),
        )
        assertEquals(expected, outputDir.resolve(file.relativePath).readText())
    }

    private val composeMultiplatformTemplate: AmperProjectTemplate
        get() = AmperProjectTemplates.availableTemplates.single { it.id == "compose-multiplatform" }

    private fun AmperProjectTemplate.fileContent(relativePath: String): String =
        listFiles().single { it.relativePath == relativePath }.resourceUrl.readText()

    private fun AmperProjectTemplate.extractTo(
        outputDir: Path,
        projectId: String = TestProjectId,
        projectName: String,
    ) = listFiles().forEach { it.extractTo(outputDir, projectId = projectId, projectName = projectName) }

    private fun assertNoPlaceholdersLeftIn(outputDir: Path) {
        outputDir.walk().filter { it.isRegularFile() && !it.isBinary() }.forEach { file ->
            val text = file.readText()
            assertFalse(
                TemplatePlaceholder.entries.any { it.text in text },
                "Unsubstituted placeholder left in ${outputDir.relativize(file)}",
            )
        }
    }

    private fun Path.isBinary(): Boolean = readBytes().isBinary()

    private fun ByteArray.isBinary(): Boolean = try {
        '\u0000' in decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        true
    }

    private fun withTempDir(test: (Path) -> Unit) {
        val tempDir = createTempDirectory("amper-project-templates-test")
        try {
            test(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
