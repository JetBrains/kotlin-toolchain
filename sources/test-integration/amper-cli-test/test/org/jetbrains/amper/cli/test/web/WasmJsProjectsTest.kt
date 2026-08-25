/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.web

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertFileExists
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.junit.jupiter.api.Tag
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-web")
class WasmJsProjectsTest : CliTestBase() {

    @Test
    fun `wasm js app compose should build with index html`() = runSlowTest {

        val projectDir = testProject("wasm-js-app-with-compose")

        (projectDir / "resources" / "index.html")
            .createParentDirectories()
            .writeText(
                """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |    <meta charset="UTF-8">
            |    <style>
            |       html, body {
            |           width: 100%;
            |           height: 100%;
            |           margin: 0;
            |           padding: 0;
            |           overflow: hidden;
            |       }
            |    </style>
            |    <title>wasm-js-app-with-compose</title>
            |    <script src="import-map-loader.js"></script>
            |    <script src="wasm-js-app-with-compose.mjs" type="module"></script>
            |</head>
            |<body>
            |
            |</body>
            |</html>
            """.trimMargin()
            )

        val result = runCli(
            projectDir = projectDir,
            "build",
        )

        result.checkComposeApplication()
    }

    @Test
    fun `wasm js app compose should build with templated index html`() = runSlowTest {
        val projectDir = testProject("wasm-js-app-with-compose")

        (projectDir / "resources" / "index.html")
            .createParentDirectories()
            .writeText(
                """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |    <meta charset="UTF-8">
            |    <style>
            |       html, body {
            |           width: 100%;
            |           height: 100%;
            |           margin: 0;
            |           padding: 0;
            |           overflow: hidden;
            |       }
            |    </style>
            |    <title>{{kotlin.moduleName}}</title>
            |    {{kotlin.scripts}}
            |</head>
            |<body>
            |
            |</body>
            |</html>
            """.trimMargin()
            )

        val result = runCli(
            projectDir = projectDir,
            "build",
        )

        result.checkComposeApplication()
    }

    @Test
    fun `wasm js app compose should build with templated index html only mjs script`() = runSlowTest {
        val projectDir = testProject("wasm-js-app-with-compose")

        (projectDir / "resources" / "index.html")
            .createParentDirectories()
            .writeText(
                """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |    <meta charset="UTF-8">
            |    <style>
            |       html, body {
            |           width: 100%;
            |           height: 100%;
            |           margin: 0;
            |           padding: 0;
            |           overflow: hidden;
            |       }
            |    </style>
            |    <title>{{kotlin.moduleName}}</title>
            |    <script src="import-map-loader.js"></script>
            |    <script src="{{kotlin.moduleFile}}" type="module"></script>
            |</head>
            |<body>
            |
            |</body>
            |</html>
            """.trimMargin()
            )

        val result = runCli(
            projectDir = projectDir,
            "build",
        )

        result.checkComposeApplication()
    }

    @Test
    fun `wasm js app compose should build without index html`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("wasm-js-app-with-compose"),
            "build",
        )

        result.checkComposeApplication()
    }

    @Test
    fun `wasm js app compose resources are packaged into the app output`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("wasm-js-app-with-compose-resources"),
            "build",
        )

        val appOutput = result.getTaskOutputPath(":app:buildWasmJsAppWasmJsDebug")

        // Resources of the app module itself
        val ownResources = appOutput / "composeResources" / "com.example.app.gen"
        assertFileExists(ownResources / "files" / "app-text.txt")

        // Resources coming from the `shared` module dependency
        val sharedResources = appOutput / "composeResources" / "com.example.shared.gen"
        assertFileExists(sharedResources / "files" / "shared-text.txt")
        assertFileExists(sharedResources / "drawable" / "shared_icon.xml")
        assertFileExists(sharedResources / "values" / "strings.common.cvr")

        // KMP resources coming from external library dependencies (published as a separate variant)
        val externalResources = appOutput / "composeResources" /
                "com.mohamedrejeb.calf.calf_cupertino_icons.generated.resources"
        val externalFont = externalResources / "font" / "sf_symbols.ttf"
        assertFileExists(externalFont)
        // a library might publish an empty resources archive, its layout is still unpacked
        assertFileExists(appOutput / "composeResources" / "components.resources.library.generated.resources")

        // The app module ships a file at the very same path as the external library does (see its `resources` dir).
        // Resources of this module override the ones coming from its dependencies.
        // The actual content is deliberately kept out of the failure message: the file of the library is a real font.
        // It is trimmed because Git normalizes the line endings of the text file it is read from (see .gitattributes).
        val shadowingContent = "not a font, this file shadows the one published by the external library"
        assertTrue(
            message = "The resource of the app module must override the one published by the external library, " +
                    "but $externalFont is ${externalFont.fileSize()} bytes long",
        ) { externalFont.readText().trim() == shadowingContent }
    }

    private fun AmperCliResult.checkComposeApplication() {

        val buildWasmJs = getTaskOutputPath(":wasm-js-app-with-compose:buildWasmJsAppWasmJsDebug")

        val baseName = "wasm-js-app-with-compose"

        val wasmFile = buildWasmJs / "$baseName.wasm"
        val mainMjsFile = buildWasmJs / "$baseName.mjs"
        val importObjectMjsFile = buildWasmJs / "$baseName.import-object.mjs"
        val jsBuiltinsMjs = buildWasmJs / "$baseName.js-builtins.mjs"
        val indexHtml = buildWasmJs / "index.html"
        val skikoMjs = buildWasmJs / "skiko.mjs"
        val skikoWasm = buildWasmJs / "skiko.wasm"

        assertFileExists(wasmFile)
        assertFileExists(mainMjsFile)
        assertFileExists(importObjectMjsFile)
        assertFileExists(jsBuiltinsMjs)
        assertFileExists(indexHtml)

        assertContains(
            indexHtml.readText(),
            "    <title>wasm-js-app-with-compose</title>"
        )
        assertContains(
            indexHtml.readText(),
            "    <script src=\"import-map-loader.js\"></script>"
        )
        assertContains(
            indexHtml.readText(),
            "    <script src=\"wasm-js-app-with-compose.mjs\" type=\"module\"></script>"
        )

        assertFileExists(skikoMjs)
        assertFileExists(skikoWasm)

        val vendors = buildWasmJs / "vendors"

        assertFileExists(vendors)
        assertFileExists(vendors / "@js-joda/core")

        assertStdoutContains(
            "pnpm install completed successfully"
        )

        assertFileExists(buildWasmJs / "vendors" / "@js-joda" / "core")
        assertEquals(
            (buildWasmJs / "import-map-loader.js").readText(),
            """
                const script = document.createElement('script');
                script.type = 'importmap';
                script.textContent = JSON.stringify({
                    "imports": {
                        "@js-joda/core": "./vendors/@js-joda/core/dist/js-joda.esm.js"
                    }
                });
                document.currentScript.after(script);
            """.trimIndent()
        )
    }
}