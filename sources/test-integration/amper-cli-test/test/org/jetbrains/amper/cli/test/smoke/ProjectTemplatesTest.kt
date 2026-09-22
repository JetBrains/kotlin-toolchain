/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.smoke

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.UpdatedAttribute
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.xcodeProjectManagementSpans
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.telemetry.getAttribute
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.spans.SpansTestCollector
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.expect

@Tag("cli-test-group-smoke")
class ProjectTemplatesTest : CliTestBase() {
    // Please add as many checks as possible to template tests

    private fun templateNameFromTestName(name: String) = name.substringBefore(' ')

    @Test
    fun `all templates are covered`() {
        val methods = javaClass.declaredMethods.map { templateNameFromTestName(it.name) }.toSet()

        val templatesRoot = Dirs.amperSourcesRoot.resolve("amper-project-templates/resources/templates")
        val entries = templatesRoot.listDirectoryEntries()
        check(entries.size > 3) {
            "Possibly incorrect templates root: $templatesRoot"
        }
        check(entries.any { it.name == "jvm-cli" } && entries.any { it.name == "compose-multiplatform" }) {
            "Does not look like a templates root: $templatesRoot"
        }

        for (entry in entries) {
            assertContains(methods, entry.name, "Template '${entry.pathString}' is not covered by any test in " +
                    "${javaClass.simpleName}. Please add a test method named '${entry.name}'")
        }
    }

    @Test
    fun `kmp-lib`(testInfo: TestInfo) = runSlowTest {
        runInitForTemplateFromTestName(testInfo)
        // Can't easily get rid of output associated with
        // class 'World': expect and corresponding actual are declared in the same module, which will be prohibited in Kotlin 2.0.
        // See https://youtrack.jetbrains.com/issue/KT-55177
        runCli(tempRoot, "build", configureAndroidHome = true, assertEmptyStdErr = false)
    }

    @Test
    fun `jvm-cli`(testInfo: TestInfo) = runSlowTest {
        runInitForTemplateFromTestName(testInfo)
        runCli(tempRoot, "build")
    }

    @Test
    fun `compose-multiplatform`(testInfo: TestInfo) = runSlowTest {
        runInitForComposeMultiplatform()
        val result = runCli(tempRoot, "build", configureAndroidHome = true, assertEmptyStdErr = false)
        if (OsFamily.current.isMac) {
            result.assertStdoutDoesNotContain("No shared scheme `app` is found")
            result.readTelemetrySpans().assertXcodeProjectIsValid()
        }
    }

    @Test
    @MacOnly
    fun `compose-multiplatform - build debug with xcodebuild`(testInfo: TestInfo) = runSlowTest {
        val initResult = runInitForComposeMultiplatform()
        val buildDir = initResult.buildDir / "xcode"
        val result = runXcodebuild(
            "-project", "app/iosApp/module.xcodeproj",
            "-scheme", "app",
            "-configuration", "Debug",
            "-arch", "arm64",
            "-sdk", "iphonesimulator",
            "-derivedDataPath", buildDir.pathString,
        )
        assertEquals(0, result.exitCode.value)
        assertTrue {
            val appPath = buildDir / "Build" / "Products" / "Debug-iphonesimulator" / "${tempRoot.name}.app"
            appPath.isDirectory()
        }
    }

    @Test
    @MacOnly
    fun `compose-multiplatform - build release with xcodebuild`(testInfo: TestInfo) = runSlowTest {
        val initResult = runInitForComposeMultiplatform()
        val buildDir = initResult.buildDir / "xcode"
        val result = runXcodebuild(
            "-project", "app/iosApp/module.xcodeproj",
            "-scheme", "app",
            "-configuration", "Release",
            "-arch", "arm64",
            // Sdk is from the project
            "-derivedDataPath", buildDir.pathString,
            "CODE_SIGNING_ALLOWED=NO",  // To build real device arch
        )

        assertEquals(0, result.exitCode.value)
        assertTrue {
            val appPath = buildDir / "Build" / "Products" / "Release-iphoneos" / "${tempRoot.name}.app"
            appPath.isDirectory()
        }
    }

    @Test
    fun `compose-desktop`(testInfo: TestInfo) = runSlowTest {
        materializeIdeOnlyTemplate(testInfo)
        runCli(tempRoot, "build")
    }

    @Test
    fun `compose-android`(testInfo: TestInfo) = runSlowTest {
        materializeIdeOnlyTemplate(testInfo)
        runCli(tempRoot, "build", configureAndroidHome = true)
    }

    @Test
    fun `ktor-server`(testInfo: TestInfo) = runSlowTest {
        runInitForTemplateFromTestName(testInfo)

        runCli(tempRoot, "build")
    }

    @Test
    fun `spring-boot-kotlin`(testInfo: TestInfo) = runSlowTest {
        runInitForTemplateFromTestName(testInfo)

        runCli(tempRoot, "run")
    }

    private suspend fun runInitForTemplateFromTestName(testInfo: TestInfo): AmperCliResult =
        runCli(
            tempRoot,
            "init", "--from-template=${templateNameFromTestName(testInfo.testMethod.get().name)}",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

    /**
     * Generates the `compose-multiplatform` project via the parameterized flow (with all targets), which is how it
     * is created from the CLI now that the template itself is not offered via `--from-template`.
     */
    private suspend fun runInitForComposeMultiplatform(): AmperCliResult =
        runCli(
            tempRoot,
            "init", "--target-platform=android", "--target-platform=ios", "--target-platform=desktop",
            "--target-platform=web", "--target-platform=server",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

    /**
     * Materializes a template that is intentionally not creatable from the CLI by copying its resources directly,
     * so we still cover that its content builds.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun materializeIdeOnlyTemplate(testInfo: TestInfo) {
        val templateName = templateNameFromTestName(testInfo.testMethod.get().name)
        val templateDir = Dirs.amperSourcesRoot / "amper-project-templates" / "resources" / "templates" / templateName
        check(templateDir.isDirectory()) { "Template dir not found: $templateDir" }
        templateDir.copyToRecursively(tempRoot, followLinks = false, overwrite = true)
        // These templates aren't creatable via the CLI anymore, so no wrappers are generated by `init`.
        // Set them up manually so the materialized project is buildable (via `kotlin build` and xcodebuild).
        LocalAmperPublication.setupWrappersIn(tempRoot)
    }

    private fun SpansTestCollector.assertXcodeProjectIsValid() {
        // Xcode project should be generated correctly by `init` and thus not updated by the build.
        assertFalse { xcodeProjectManagementSpans.assertSingle().getAttribute(UpdatedAttribute) }
    }
}
