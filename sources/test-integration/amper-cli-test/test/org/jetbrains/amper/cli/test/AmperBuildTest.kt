/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertJavaIncrementalCompilationState
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.frontend.schema.MinVersions
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.spans.assertEachKotlinNativeCompilationSpan
import org.jetbrains.amper.test.spans.kotlinJvmCompilationSpans
import org.jetbrains.amper.test.spans.withAmperModule
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ParameterizedTest(name = "{displayName}; jps={0}")
@ValueSource(booleans = [true, false])
@Target(AnnotationTarget.FUNCTION)
private annotation class RunWithAndWithoutJic

class AmperBuildTest : AmperCliTestBase() {

    @RunWithAndWithoutJic
    fun `build command succeeds in jvm-default-compiler-settings`(compileJavaIncrementally: Boolean) = runSlowTest {
        runCliWithOrWithoutJps(
            projectDir = testProject("jvm-default-compiler-settings"),
            "build",
            compileJavaIncrementally = compileJavaIncrementally,
        )
    }

    @Test
    fun `build command produces a jar for jvm in kmp project`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("multiplatform-input"),
            "build", "-p", "jvm",
        )

        assertTrue {
            val file = result.getTaskOutputPath(":shared:jarJvm") / "shared-jvm.jar"
            file.exists()
        }
    }

    // KTC-5395
    @Test
    fun `internal declarations are accessible in native test source sets`() = runSlowTest {
        runCli(
            projectDir = testProject("ktc-5395"),
            "build", "--platform=linuxX64",
        )
    }

    @Test
    fun `incremental jvm build`() = runSlowTest {
        val projectDir = testProject("incremental-compilation")
        val result1 = runCli(projectDir = projectDir, "build")
        result1.withTelemetrySpans {
            kotlinJvmCompilationSpans.withAmperModule("shared").assertTimes(2) // main & test
            kotlinJvmCompilationSpans.withAmperModule("app").assertSingle() // no tests
        }

        val result2 = runCli(projectDir = projectDir, "build")
        result2.withTelemetrySpans {
            kotlinJvmCompilationSpans.assertNone() // no recompilation (avoidance)
        }

        val resultRun = runCli(projectDir = projectDir, "run")
        resultRun.assertStdoutContains("Hello, World!")

        val appClasses = resultRun.buildDir / "artifacts/CompiledJvmArtifact/appjvm/kotlin-output"
        val appClassesState = computeFileStates(appClasses)

        projectDir.resolve("shared/src/World.kt").replaceInText("\"World\"", "\"New World\"")

        val resultModifiedRun = runCli(projectDir = projectDir, "run")
        resultModifiedRun.withTelemetrySpans {
            kotlinJvmCompilationSpans.withAmperModule("shared").assertSingle() // tests don't need recompilation
            // we still have a span for the main compilation because IC operates inside it
            kotlinJvmCompilationSpans.withAmperModule("app").assertSingle()
        }
        resultModifiedRun.assertStdoutContains("Hello, New World!")
        assertEquals(appClassesState, computeFileStates(appClasses), "Classes in 'app' module shouldn't have changed thanks to incremental compilation")

        val worldClass = resultModifiedRun.buildDir / "artifacts/CompiledJvmArtifact/sharedjvm/kotlin-output/World.class"
        assertEquals(65 /* for Java 21 */, majorClassVersion(worldClass), "Classes should initially target bytecode 21 (major version 65)")

        projectDir.resolve("common.module-template.yaml").replaceInText("release: 21", "release: 17")
        runCli(projectDir = projectDir, "build")
        assertEquals(61 /* for Java 17 */, majorClassVersion(worldClass), "Classes should be recompiled to bytecode 17 (major version 61)")
    }

    private fun majorClassVersion(classFile: Path): Int {
        val classBytes = classFile.readBytes() // it's small enough to be OK
        // sanity check that we're reading a class file
        check(0xCA.toByte() == classBytes[0])
        check(0xFE.toByte() == classBytes[1])
        check(0xBA.toByte() == classBytes[2])
        check(0xBE.toByte() == classBytes[3])

        return ((classBytes[6].toInt() and 0xFF) shl 8) or
                (classBytes[7].toInt() and 0xFF)
    }

    private fun computeFileStates(dir: Path): List<String> = dir.listDirectoryEntries()
        .map { "${it.name}-${it.getLastModifiedTime().toInstant()}" }
        .sorted()

    private fun Path.replaceInText(oldValue: String, newValue: String) {
        writeText(readText().replace(oldValue, newValue))
    }

    @RunWithAndWithoutJic
    fun `build jar with main class`(useJavaIncrementalCompilation: Boolean) = runSlowTest {
        val result = runCliWithOrWithoutJps(
            projectDir = testProject("java-kotlin-mixed"),
            "build",
            compileJavaIncrementally = useJavaIncrementalCompilation,
            )

        val jarPath = result.getTaskOutputPath(":java-kotlin-mixed:jarJvm").resolve("java-kotlin-mixed-jvm.jar")
        assertTrue(jarPath.isRegularFile(), "${jarPath.pathString} should exist and be a file")

        JarFile(jarPath.toFile()).use { jar ->
            val mainClass = jar.manifest.mainAttributes[Attributes.Name.MAIN_CLASS] as? String
            assertNotNull(mainClass, "The ${Attributes.Name.MAIN_CLASS} attribute should be present")
            assertEquals("bpkg.MainKt", mainClass)

            val entryNames = jar.entries().asSequence().map { it.name }.toList()
            val expectedEntriesInOrder = listOf(
                "META-INF/MANIFEST.MF",
                "META-INF/",
                "META-INF/java-kotlin-mixed.kotlin_module",
                "apkg/",
                "apkg/AClass.class",
                "bpkg/",
                "bpkg/BClass.class",
                "bpkg/MainKt.class",
            )
            assertEquals(expectedEntriesInOrder, entryNames)
        }
    }

    @Test
    fun `failed kotlinc compilation message`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multi-module-failed-kotlinc-compilation"),
            "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val path = Path("shared/src/World.kt")
        val expectedStderr = """
                ╭─ ERROR: Unresolved reference 'XXXX'.
                │ → $path:2:26 (shared)
                │
              2 │     fun get() : String = XXXX
                │                          ⌃⌃⌃⌃
                ╰─
            
            ERROR: Task ':shared:compileJvm' failed: Kotlin compilation failed with 1 errors (see above)
        """.trimIndent()
        assertEquals(expectedStderr.trim(), r.stderr.trim())
    }

    @Test
    fun `simple multiplatform cli should compile windows on any platform`() = runSlowTest {
        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(projectDir = projectContext, "build", "--platform=mingwX64")

        assertTrue("build must generate a 'windows-cli.exe' file somewhere") {
            result.buildDir.walk().any { it.name == "windows-cli.exe" }
        }
    }

    @Test
    fun `failed dependency resolution message`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multi-module-failed-resolve"),
            "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        // The order and set of "ERROR: Task ..." lines is non-deterministic depending on task execution
        // order and which tasks fail first, so we exclude them here and assert on the diagnostic block only.
        val actualStderr = r.stderr.lines()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("ERROR: Task ") }
            .joinToString("\n")

        val expected = """
        |    ╭─ ERROR: Unable to resolve dependency org.junit.jupiter:junit-jupiter-api:9999
        |    │   Unable to download checksums of file junit-jupiter-api-9999.pom
        |    │   Unable to download checksums of file junit-jupiter-api-9999.module
        |    │ Repositories used for resolution:
        |    │   - https://cache-redirector.jetbrains.com/kotlin/repo1.maven.org/maven2
        |    │   - https://maven.google.com
        |    │
        |    │ → shared${File.separator}module.yaml:6:5
        |    │
        |  6 │   - org.junit.jupiter:junit-jupiter-api:9999
        |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
        |    ╰─
        """.trimMargin()

        assertEquals(expected, actualStderr)
    }

    @Test
    fun `run build issues warning about unsupported build variant`() = runSlowTest {
        val projectRoot = testProject("jvm-resources")

        val result1 = runCli(
            projectDir = projectRoot,
            "build", "-v", "debug",
        )

        result1.assertStdoutContains(
            "Explicit -v/--variant argument is ignored because none of the selected platforms (jvm) support build variants."
        )

        val result2 = runCli(
            projectDir = projectRoot,
            "build",
        )

        result2.assertStdoutDoesNotContain(
            "Explicit -v/--variant argument is ignored because none of the selected platforms (jvm) support build variants."
        )
    }

    @Test
    fun `native linker options are respected`() = runSlowTest {
        val projectRoot = testProject("native-linker-options")
        val result = runCli(projectDir = projectRoot, "build")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-linker-option=-Wl,--as-needed")
            hasCompilerArgument("-linker-option=-Wl,-Bstatic")
            hasCompilerArgument("-linker-option=-lz")
        }
    }

    @Test
    fun `kotlin compiler dev version`() = runSlowTest {
        val projectContext = testProject("kotlin-dev-version")
        runCli(projectDir = projectContext, "build") // just test that it builds
    }

    @Test
    fun `kotlin errors are reported structurally`() = runSlowTest {
        val projectContext = testProject("kotlin-diagnostics-errors")
        val result = runCli(
            projectDir = projectContext,
            "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val filePath = Path("src/main.kt").pathString

        result.assertStderrContains("""
           |    ╭─ ERROR: Cannot infer type for type parameter 'B'. Specify it explicitly.
           |    │ → $filePath:8:9 (kotlin-diagnostics-errors)
           |    │
           |  8 │     "a" to unknownValue
           |    │         ⌃⌃
           |    ╰─
        """.trimMargin())

        result.assertStderrContains("""
           |    ╭─ ERROR: Unresolved reference 'unknownValue'.
           |    │ → $filePath:8:12 (kotlin-diagnostics-errors)
           |    │
           |  8 │     "a" to unknownValue
           |    │            ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
           |    ╰─
        """.trimMargin())

        result.assertStderrContains("""
           |    ╭─ ERROR: Argument type mismatch: actual type is 'String', but 'Int' was expected.
           |    │ → $filePath:10:9 (kotlin-diagnostics-errors)
           |    │
           |    │         ⌄⌄⌄
           | 10 │     foo(""${'"'}
           | 11 │         multiline
           | 12 │     ""${'"'}.trimIndent())
           |    │ ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
           |    ╰─
        """.trimMargin())
    }

    @Test
    fun `kotlin warnings are reported structurally`() = runSlowTest {
        val projectContext = testProject("kotlin-diagnostics-warnings")
        val result = runCli(projectDir = projectContext, "build")
        val filePath = Path("src/main.kt").pathString
        result.assertStdoutContains("""
           |    ╭─ WARNING: Unused return value of 'foo'.
           |    │ → $filePath:9:5 (kotlin-diagnostics-warnings)
           |    │
           |  9 │     foo()
           |    │     ⌃⌃⌃
           |    ╰─
        """.trimMargin())

        result.assertStdoutContains("""
           |    ╭─ WARNING: Expression is unused.
           |    │ → $filePath:11:5 (kotlin-diagnostics-warnings)
           |    │
           |    │     ⌄⌄⌄
           | 11 │     ""${'"'}
           | 12 │         multiline
           | 13 │     ""${'"'}
           |    │ ⌃⌃⌃⌃⌃⌃⌃
           |    ╰─
        """.trimMargin())
    }

    @Test
    fun `kotlin warnings are replayed from incremental cache`() = runSlowTest {
        val projectContext = testProject("kotlin-diagnostics-warnings")
        val filePath = Path("src/main.kt").pathString
        val firstWarning = """
           |    ╭─ WARNING: Unused return value of 'foo'.
           |    │ → $filePath:9:5 (kotlin-diagnostics-warnings)
           |    │
           |  9 │     foo()
           |    │     ⌃⌃⌃
           |    ╰─
        """.trimMargin()

        val firstRun = runCli(projectDir = projectContext, "build")
        firstRun.assertStdoutContains(firstWarning)

        val secondRun = runCli(projectDir = projectContext, "build")
        secondRun.readTelemetrySpans().kotlinJvmCompilationSpans.assertNone()
        secondRun.assertStdoutContains(firstWarning)
    }

    @Test
    fun `wasm js app should compile wasm js`() = runSlowTest {
        val projectContext = testProject("wasm-js-app")
        val result = runCli(projectDir = projectContext, "build", "--platform=wasmJs")

        assertTrue {
            val wasmFile = result.getTaskOutputPath(":wasm-js-app:buildWasmJsAppWasmJsDebug") / "wasm-js-app.wasm"
            wasmFile.exists()
        }
    }

    // JIC doesn't have the same lowest JDK, so this is not redundant with the other "lowest JDK for JVM" test
    @Test
    fun `build Java incrementally works with lowest supported JDK`() = runSlowTest {
        runCli(projectDir = testProject("java-ic-lowest-jdk"), "build")
    }

    @Test
    fun `jvm hello world with lowest supported JDK`() = runSlowTest {
        val projectDir = testProject("jvm-custom-jdk-lowest")
        val moduleFile = projectDir.resolve("module.yaml")
        moduleFile.writeText(moduleFile.readText().replace("{{MIN_JDK_VERSION}}", MinVersions.jdk.toString()))
        runCli(projectDir = projectDir, "build")
    }

    @RunWithAndWithoutJic
    fun `jvm hello world with highest supported JDK`(compileJavaIncrementally: Boolean) = runSlowTest {
        runCliWithOrWithoutJps(
            projectDir = testProject("jvm-custom-jdk-highest"),
            "build",
            compileJavaIncrementally = compileJavaIncrementally,
        )
    }

    @Test
    fun `multiplatform lib with highest known JDK`() = runSlowTest {
        runCli(projectDir = testProject("multiplatform-highest-jdk"), "build", configureAndroidHome = true)
    }

    @Test
    fun `multiplatform lib with lowest supported JDK`() = runSlowTest {
        val projectDir = testProject("multiplatform-lowest-jdk-and-kotlin")
        val moduleFile = projectDir.resolve("module.yaml")
        moduleFile.writeText(
            moduleFile.readText()
                .replace("{{MIN_KOTLIN_VERSION}}", MinVersions.kotlin.canonical)
                .replace("{{MIN_JDK_VERSION}}", MinVersions.jdk.toString())
        )
        val result = runCli(
            projectDir = projectDir,
            "build",
            configureAndroidHome = true,
            assertEmptyStdErr = false,
        )
        // We're using a low Kotlin version while the native compiler uses the default JDK as a JRE to run.
        // This combination generates these expected warnings about Unsafe usages (which were fixed in later compiler
        // versions). We can ignore them for now until our minimum version reaches 2.4.10.
        val expectedWarnings = [
            "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called",
            "WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.jetbrains.kotlin.com.intellij.util.containers.Unsafe",
            "WARNING: Please consider reporting this to the maintainers of class org.jetbrains.kotlin.com.intellij.util.containers.Unsafe",
            "WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release",
        ]
        val filteredStderr = result.stderr.lines().filter { line -> expectedWarnings.none { it in line } }
        assertEqualsWithDiff([], filteredStderr, "stderr should only contain expected warnings")
    }

    // AMPER-5259
    @Test
    fun `AARs in test dependencies are properly processed`() = runSlowTest {
        runCli(projectDir = testProject("android-test-dependency"), "build", configureAndroidHome = true)
    }

    private suspend fun runCliWithOrWithoutJps(
        projectDir: Path,
        vararg args: String,
        compileJavaIncrementally: Boolean,
    ): AmperCliResult {
        val result = runCli(
            projectDir = projectDir,
            *args,
            amperJvmArgs = listOf("-Dorg.jetbrains.amper.jic=${compileJavaIncrementally}"),
        )
        result.assertJavaIncrementalCompilationState(compileJavaIncrementally)
        return result
    }
}
