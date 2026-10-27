/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import org.jetbrains.amper.templates.TemplatePlaceholder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TestProjectId = "org.example.project"

class KotlinMultiplatformProjectGeneratorTest {

    @Test
    fun `validates the conservative cross-platform project id contract`() {
        val accepted = [
            "a.b",
            "org.example.myapp2",
            "com.example.data",
            "com.example.module",
            "com.example.record",
            "com.example.kotlin",
        ]
        val rejected = [
            "",
            " ",
            "com.example\napp",
            "com.example\n",
            " com.example",
            "com.example ",
            "com.\tapp",
            "com.`when`",
            "com._app",
            "com._",
            "com.as?",
            "com.!in",
            "com.!is",
            "myapp",
            ".com.app",
            "com..app",
            "com.app.",
            "com.2app",
            "com.my_app",
            "com.my-app",
            "com.café",
            "Com.example.app",
            "com.Example.app",
            "com.example.when",
            "com.example.int",
            "com.example.null",
            "kotlin.example",
            "java.example",
        ]

        accepted.forEach { checkValidProjectId(it) }
        rejected.forEach {
            assertFailsWith<InvalidProjectIdException> { checkValidProjectId(it) }
        }
    }

    @Test
    fun `reports one violated project id rule`() {
        val expectedReasonByProjectId = mapOf(
            "" to "the package name must not be empty",
            "myapp" to "the package name must have at least 2 segments separated with a dot",
            "1app" to "the package name must have at least 2 segments separated with a dot",
            " " to "the package name must have at least 2 segments separated with a dot",
            ".org.app" to "package name segments must not be empty",
            "org..app" to "package name segments must not be empty",
            "org.app." to "package name segments must not be empty",
            "1nvalid id" to "the package name must have at least 2 segments separated with a dot",
            "org.my_app" to "package name segments must contain only lowercase ASCII letters and digits",
            "org.2app" to "each package name segment must start with a letter",
            "java.example" to "'java' cannot be used as the first package name segment",
            "kotlin.example" to "'kotlin' cannot be used as the first package name segment",
            "org.example.when" to "'when' is a reserved keyword and cannot be used as a package name segment",
        )

        expectedReasonByProjectId.forEach { (key, value) ->
            val error = assertFailsWith<InvalidProjectIdException> { checkValidProjectId(key) }
            assertEquals("Invalid project id '$key': $value.", error.message)
        }
    }

    @Test
    fun `preserves the original package character rules`() {
        val originalPattern = Regex("""[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+""")
        for (codePoint in 0..255) {
            val char = codePoint.toChar()
            for (projectId in ["${char}aa.bb", "aa${char}.bb", "aa.${char}bb", "aa.bb${char}"]) {
                val accepted = try {
                    checkValidProjectId(projectId)
                    true
                } catch (_: InvalidProjectIdException) {
                    false
                }
                assertEquals(originalPattern.matches(projectId), accepted, "Character code: $codePoint")
            }
        }
    }

    @Test
    fun `rejects every Kotlin hard keyword and Java reserved word or literal`() {
        val forbiddenSegments = [
            // Kotlin hard keywords.
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when", "while",
            // Java reserved keywords not already listed above.
            "abstract", "assert", "boolean", "byte", "case", "catch", "char", "const", "default", "double",
            "enum", "extends", "final", "finally", "float", "goto", "implements", "import", "instanceof", "int",
            "long", "native", "new", "private", "protected", "public", "short", "static", "strictfp", "switch",
            "synchronized", "throws", "transient", "void", "volatile",
        ]

        forbiddenSegments.forEach { segment ->
            assertFailsWith<InvalidProjectIdException>("Expected '$segment' to be forbidden") {
                checkValidProjectId("org.example.$segment")
            }
        }
    }

    @Test
    fun `allows Kotlin soft and Java contextual keywords`() {
        val allowedSegments = ["actual", "data", "module", "open", "record", "sealed", "yield"]

        allowedSegments.forEach { segment ->
            checkValidProjectId("org.example.$segment")
        }
    }

    @Test
    fun `only rejects java and kotlin as root segments`() {
        assertFailsWith<InvalidProjectIdException> { checkValidProjectId("java.example") }
        assertFailsWith<InvalidProjectIdException> { checkValidProjectId("kotlin.example") }
        checkValidProjectId("org.example.java")
        checkValidProjectId("org.example.kotlin")
    }

    @Test
    fun `accepts long project ids`() {
        checkValidProjectId("org.example.${"a".repeat(500)}")
    }

    @Test
    fun `derives valid project ids from project names`() {
        val cases = mapOf(
            "My App" to "org.example.myapp",
            "hello_world.demo-app" to "org.example.helloworlddemoapp",
            "123-demo" to "org.example.project123demo",
            "class" to "org.example.projectclass",
            "NULL" to "org.example.projectnull",
            "int" to "org.example.projectint",
            "I" to "org.example.i",
            "Café" to "org.example.caf",
            "日本語" to "org.example.project",
            "!!!" to "org.example.project",
            "" to "org.example.project",
            "A".repeat(500) to "org.example.${"a".repeat(500)}",
            "1".repeat(500) to "org.example.project${"1".repeat(500)}",
        )

        cases.forEach { [projectName, expectedId] ->
            val actualId = defaultProjectId(projectName)
            assertEquals(expectedId, actualId, "Unexpected default for '$projectName'")
            checkValidProjectId(actualId)
        }
    }

    @Test
    fun `rejects an invalid project id at the generator boundary`() {
        val error = assertFailsWith<InvalidProjectIdException> {
            generate(projectId = "com.my_app")
        }

        assertEquals(
            "Invalid project id 'com.my_app': " +
                    "package name segments must contain only lowercase ASCII letters and digits.",
            error.message,
        )
    }

    @Test
    fun `uses the validated project id for every generated platform identifier`() {
        val projectId = "com.acme.demo"
        val projectName = "My App_name-with-hyphens"
        val files = KotlinMultiplatformProjectGenerator.generate(
            targets = KotlinMultiplatformTargetPlatform.entries.toSet(),
            projectId = projectId,
            projectName = projectName,
        ).associateBy { it.relativePath }

        assertContains(files.getValue("core/src/GreetingUtil.kt").text(), "package $projectId")
        assertContains(files.getValue("server/src/Application.kt").text(), "package $projectId.server")
        assertContains(files.getValue("app/shared/src/App.kt").text(), "package $projectId")
        assertContains(files.getValue("app/shared/src/App.kt").text(), "import $projectId.resources.Res")
        assertContains(files.getValue("app/shared/module.yaml").text(), "packageName: $projectId.resources")
        assertContains(files.getValue("app/androidApp/module.yaml").text(), "applicationId: $projectId")

        val xcodeProject = files.getValue("app/iosApp/module.xcodeproj/project.pbxproj").text()
        assertEquals(2, Regex.fromLiteral("PRODUCT_BUNDLE_IDENTIFIER = \"$projectId\";").findAll(xcodeProject).count())
        assertFalse("$projectId.$projectName" in xcodeProject)
        assertFalse("$projectId.composeApp" in xcodeProject)
        assertEquals(2, Regex.fromLiteral("PRODUCT_NAME = \"$projectName\";").findAll(xcodeProject).count())

        assertContains(files.getValue("app/androidApp/res/values/strings.xml").text(), ">$projectName</string>")
        assertContains(files.getValue("app/desktopApp/src/main.kt").text(), "title = \"$projectName\"")
        assertContains(files.getValue("app/webApp/resources/index.html").text(), "<title>$projectName</title>")
    }

    @Test
    fun `leaves no template placeholder in any generated file`() {
        val files = KotlinMultiplatformProjectGenerator.generate(
            targets = KotlinMultiplatformTargetPlatform.entries.toSet(),
            projectId = "com.acme.demo",
            projectName = "My App",
        )

        files.filterNot { it.relativePath.endsWith(".png") }.forEach { file ->
            assertFalse(
                TemplatePlaceholder.entries.any { it.text in file.text() },
                "Unsubstituted placeholder left in '${file.relativePath}'",
            )
        }
    }

    @Test
    fun `quotes generated Xcode product paths containing spaces`() {
        val xcodeProject = KotlinMultiplatformProjectGenerator.generate(
            targets = [KotlinMultiplatformTargetPlatform.IOS],
            projectId = "com.acme.demo",
            projectName = "My App",
        ).single { it.relativePath == "iosApp/module.xcodeproj/project.pbxproj" }.text()

        assertContains(xcodeProject, "path = \"My App.app\";")
        assertFalse("path = My App.app;" in xcodeProject)
    }

    @Test
    fun `escapes project names in every generated text format`() {
        val projectName = "Bob's & <App> \"\\$"
        val files = KotlinMultiplatformProjectGenerator.generate(
            targets = KotlinMultiplatformTargetPlatform.entries.toSet(),
            projectId = "com.acme.demo",
            projectName = projectName,
        ).associateBy { it.relativePath }

        val escapedXml = "Bob&apos;s &amp; &lt;App&gt; &quot;\\$"
        val escapedAndroidString = "Bob\\'s &amp; &lt;App&gt; \\\"\\\\$"
        val escapedKotlinString = """Bob's & <App> \"\\\$"""
        val escapedPbxString = """Bob's & <App> \"\\$$"""
        val xcodeProject = files.getValue("app/iosApp/module.xcodeproj/project.pbxproj").text()
        assertContains(xcodeProject, "path = \"$escapedPbxString.app\";")
        assertEquals(2, Regex.fromLiteral("PRODUCT_NAME = \"$escapedPbxString\";").findAll(xcodeProject).count())
        assertContains(
            files.getValue("app/androidApp/res/values/strings.xml").text(),
            ">$escapedAndroidString</string>",
        )
        assertContains(files.getValue("app/desktopApp/src/main.kt").text(), "title = \"$escapedKotlinString\"")
        assertContains(files.getValue("app/webApp/resources/index.html").text(), "<title>$escapedXml</title>")
        assertEquals(
            2,
            Regex.fromLiteral("BuildableName = \"$escapedXml.app\"")
                .findAll(files.getValue("app/iosApp/module.xcodeproj/xcshareddata/xcschemes/app.xcscheme").text())
                .count(),
        )
    }

    @Test
    fun `escapes Xcode build setting references in project names`() {
        val dollar = '$'
        val projectName = "$dollar(SRCROOT) $dollar{PRODUCT_NAME} $dollar"
        val escapedProjectName = "$dollar$dollar(SRCROOT) $dollar$dollar{PRODUCT_NAME} $dollar$dollar"
        val xcodeProject = KotlinMultiplatformProjectGenerator.generate(
            targets = [KotlinMultiplatformTargetPlatform.IOS],
            projectId = "com.acme.demo",
            projectName = projectName,
        ).single { it.relativePath == "iosApp/module.xcodeproj/project.pbxproj" }.text()

        assertContains(xcodeProject, "path = \"$escapedProjectName.app\";")
        assertEquals(2, Regex.fromLiteral("PRODUCT_NAME = \"$escapedProjectName\";").findAll(xcodeProject).count())
    }

    @ParameterizedTest
    @ValueSource(strings = ["@MyApp", "?MyApp"])
    fun `escapes Android resource reference prefixes in project names`(projectName: String) {
        val stringsXml = KotlinMultiplatformProjectGenerator.generate(
            targets = [KotlinMultiplatformTargetPlatform.ANDROID],
            projectId = "com.acme.demo",
            projectName = projectName,
        ).single { it.relativePath == "androidApp/res/values/strings.xml" }.text()

        assertContains(stringsXml, ">\\$projectName</string>")
    }

    @ParameterizedTest
    @ValueSource(strings = [" My App", "My  App", "My App "])
    fun `preserves Android project name whitespace`(projectName: String) {
        val stringsXml = KotlinMultiplatformProjectGenerator.generate(
            targets = [KotlinMultiplatformTargetPlatform.ANDROID],
            projectId = "com.acme.demo",
            projectName = projectName,
        ).single { it.relativePath == "androidApp/res/values/strings.xml" }.text()

        assertContains(stringsXml, ">\"$projectName\"</string>")
    }

    @Test
    fun `extraction schema generates template AGENTS md unless no-ai is given`() {
        val mode = KotlinMultiplatformApp(
            targets = [KotlinMultiplatformTargetPlatform.ANDROID],
            projectId = TestProjectId,
            projectName = "Sample App",
        )

        val withAi = mode.toExtractionSchema().files.singleOrNull { it.relativePath == "AGENTS.md" }
        assertNotNull(withAi, "AGENTS.md should come from the Kotlin Multiplatform template")
        assertContains(withAi.text(), "Kotlin Toolchain")

        val withoutAi = mode.toExtractionSchema(generateAiFiles = false).relativePaths
        assertFalse("AGENTS.md" in withoutAi)
    }

    @Test
    fun `AGENTS md has UI development section only when desktop target is selected`() {
        fun agentsMd(target: KotlinMultiplatformTargetPlatform) =
            KotlinMultiplatformApp([target], TestProjectId, "Sample App").toExtractionSchema().files
                .single { it.relativePath == "AGENTS.md" }.text()

        val withDesktop = agentsMd(KotlinMultiplatformTargetPlatform.DESKTOP)
        assertContains(withDesktop, "## UI Development")
        assertContains(withDesktop, "Kotlin Toolchain")

        val withoutDesktop = agentsMd(KotlinMultiplatformTargetPlatform.ANDROID)
        assertContains(withoutDesktop, "Kotlin Toolchain")
        assertFalse("UI Development" in withoutDesktop)
    }

    @Test
    fun `extraction schema can omit Git files`() {
        val mode = KotlinMultiplatformApp(
            targets = [KotlinMultiplatformTargetPlatform.ANDROID],
            projectId = TestProjectId,
            projectName = "Sample App",
        )

        val paths = mode.toExtractionSchema(generateGitFiles = false).relativePaths
        assertFalse(".gitignore" in paths)
        assertFalse(".gitattributes" in paths)
    }

    @Test
    fun `extraction schema generates the gitattributes at the project root`() {
        val mode = KotlinMultiplatformApp(
            targets = [KotlinMultiplatformTargetPlatform.DESKTOP],
            projectId = TestProjectId,
            projectName = "Sample App",
        )

        assertContains(mode.toExtractionSchema().relativePaths, ".gitattributes")
    }

    @Test
    fun `generates the gitattributes with PNG rules because the template has PNG files`() {
        val app = KotlinMultiplatformApp(
            targets = [KotlinMultiplatformTargetPlatform.DESKTOP],
            projectId = TestProjectId,
            projectName = "Sample App",
        )
        val gitAttributes = app.toExtractionSchema().files.single { it.relativePath == ".gitattributes" }

        assertEquals(expectedGitAttributes(hasPngFiles = true), gitAttributes.text())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `does not generate a module-level gitignore for the Android app`(serverIncluded: Boolean) {
        val targets: Set<KotlinMultiplatformTargetPlatform> = if (serverIncluded) {
            [KotlinMultiplatformTargetPlatform.ANDROID, KotlinMultiplatformTargetPlatform.SERVER]
        } else {
            [KotlinMultiplatformTargetPlatform.ANDROID]
        }
        val mode = KotlinMultiplatformApp(
            targets = targets,
            projectId = TestProjectId,
            projectName = "Sample App",
        )
        val gitignorePaths = mode.toExtractionSchema().relativePaths.filter { it.endsWith(".gitignore") }

        assertEquals([".gitignore"], gitignorePaths)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `generates Compose root gitignore without generating IDEA files`(serverIncluded: Boolean) {
        val targets: Set<KotlinMultiplatformTargetPlatform> = if (serverIncluded) {
            [KotlinMultiplatformTargetPlatform.DESKTOP, KotlinMultiplatformTargetPlatform.SERVER]
        } else {
            [KotlinMultiplatformTargetPlatform.DESKTOP]
        }
        val mode = KotlinMultiplatformApp(
            targets = targets,
            projectId = TestProjectId,
            projectName = "Sample App",
        )
        val files = mode.toExtractionSchema().files
        val gitignore = assertNotNull(files.singleOrNull { it.relativePath == ".gitignore" })
        assertEquals(KotlinMultiplatformGitIgnore + mcpConfigGitIgnoreEntries(AiAgent.entries.toSet()).joinToString("") { "$it\n" }, gitignore.text())
        assertContains(gitignore.text(), "/.mcp.json\n")

        assertFalse(files.any { it.relativePath.startsWith(".idea/") })
    }

    @Test
    fun `modules list regex accepts Windows line endings`() {
        val modulesListRegex = KotlinMultiplatformProjectGenerator::class.java
            .getDeclaredField("ModulesListRegex")
            .apply { isAccessible = true }
            .get(KotlinMultiplatformProjectGenerator) as Regex
        val projectYaml = "modules:\r\n  - desktopApp\r\n  - shared\r\n"

        assertNotNull(modulesListRegex.find(projectYaml))
    }

    @Test
    fun `core dependency regex accepts Windows line endings`() {
        val coreDependencyRegex = KotlinMultiplatformProjectGenerator::class.java
            .getDeclaredField("CoreDependencyRegex")
            .apply { isAccessible = true }
            .get(KotlinMultiplatformProjectGenerator) as Regex
        val moduleYaml = "dependencies:\r\n  - //core\r\n  - other\r\n"

        assertEquals("dependencies:\r\n  - other\r\n", coreDependencyRegex.replace(moduleYaml, ""))
    }

    @Test
    fun `readme has a heading and only describes selected modules`() {
        val readme = generate().single { it.relativePath == "README.md" }.text()
        assertTrue(readme.startsWith("# "))
        assertContains(readme, "targeting Desktop (fast UI development with Compose Hot Reload), built with")
        assertContains(readme, "https://kotlin-toolchain.org/latest/")
        assertFalse("greeting helper" in readme)
        assertFalse("run widget" in readme)
        assertFalse("/server" in readme)
    }

    @Test
    fun `always generates sample tests for the selected targets`() {
        val paths = KotlinMultiplatformProjectGenerator.generate(
            targets = [KotlinMultiplatformTargetPlatform.DESKTOP, KotlinMultiplatformTargetPlatform.SERVER],
            projectId = TestProjectId,
            projectName = "Sample App",
        ).map { it.relativePath }

        assertContains(paths, "app/shared/test/PlatformTest.kt")
        assertContains(paths, "app/shared/test@jvm/PlatformJvmTest.kt")
    }

    private fun generate(
        projectId: String = TestProjectId,
    ): List<GeneratedFile> = KotlinMultiplatformProjectGenerator.generate(
        targets = [KotlinMultiplatformTargetPlatform.DESKTOP],
        projectId = projectId,
        projectName = "Sample App",
    )

    private fun expectedGitAttributes(hasPngFiles: Boolean): String = buildString {
        append("* text=auto\n\n")
        append("*.sh text eol=lf\n")
        append("*.bat text eol=crlf\n")
        if (hasPngFiles) append("\n*.png binary\n")
        append("\n# Kotlin CLI wrapper without extension\n")
        append("kotlin text eol=lf\n")
    }

    private fun GeneratedFile.text(): String = when (this) {
        is BinaryGeneratedFile -> content.contentToString()
        is TextGeneratedFile -> content
    }


    private val cmpDesktopMode get() = KotlinMultiplatformApp(
        targets = [KotlinMultiplatformTargetPlatform.DESKTOP],
        projectId = TestProjectId,
        projectName = "Sample App",
    )

    private val allMcpPaths = [
        ".mcp.json",
        ".codex/config.toml",
        ".agents/mcp_config.json",
        ".junie/mcp/mcp.json",
        ".pi/mcp.json",
        ".opencode/opencode.json",
    ]

    @Test
    fun `empty agents set omits MCP configs in CMP mode`() {
        val paths = cmpDesktopMode.toExtractionSchema(agents = []).relativePaths
        for (mcpPath in allMcpPaths) {
            assertFalse(mcpPath in paths, "$mcpPath should be absent when agents is empty")
        }
    }

    @Test
    fun `all-agents default produces all six MCP files`() {
        val paths = cmpDesktopMode.toExtractionSchema().relativePaths
        for (mcpPath in allMcpPaths) {
            assertContains(paths, mcpPath)
        }
    }

    @ParameterizedTest
    @CsvSource(value = [
        "CLAUDE, .mcp.json",
        "CODEX, .codex/config.toml",
        "ANTIGRAVITY, .agents/mcp_config.json",
        "JUNIE, .junie/mcp/mcp.json",
        "PI, .pi/mcp.json",
        "OPENCODE, .opencode/opencode.json",
    ])
    fun `single agent generates only its config`(agent: AiAgent, expectedPath: String) {
        val paths = cmpDesktopMode.toExtractionSchema(agents = [agent]).relativePaths
        assertContains(paths, expectedPath)
        for (mcpPath in allMcpPaths - expectedPath) {
            assertFalse(mcpPath in paths, "$mcpPath should be absent when only $agent is selected")
        }
    }

    @Test
    fun `Junie and Pi share claude schema content even when Claude not selected`() {
        val juniePaths = cmpDesktopMode.toExtractionSchema(agents = [AiAgent.JUNIE, AiAgent.PI]).relativePaths
        assertContains(juniePaths, ".junie/mcp/mcp.json")
        assertContains(juniePaths, ".pi/mcp.json")
        assertFalse(".mcp.json" in juniePaths)

        val files = cmpDesktopMode.toExtractionSchema(agents = [AiAgent.JUNIE, AiAgent.PI]).files
        val junieContent = (files.single { it.relativePath == ".junie/mcp/mcp.json" } as TextGeneratedFile).content
        val piContent = (files.single { it.relativePath == ".pi/mcp.json" } as TextGeneratedFile).content
        assertEquals(junieContent, piContent, "Junie and Pi should get identical shared-schema content")
        assertContains(junieContent, "mcpServers")
    }

    @Test
    fun `Claude and Junie and Pi all share same config content`() {
        val files = cmpDesktopMode.toExtractionSchema(agents = AiAgent.entries.toSet()).files
        val claudeContent = (files.single { it.relativePath == ".mcp.json" } as TextGeneratedFile).content
        val junieContent = (files.single { it.relativePath == ".junie/mcp/mcp.json" } as TextGeneratedFile).content
        val piContent = (files.single { it.relativePath == ".pi/mcp.json" } as TextGeneratedFile).content
        assertEquals(claudeContent, junieContent)
        assertEquals(claudeContent, piContent)
    }


}
