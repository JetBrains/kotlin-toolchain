/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the `unique_name` and `short_name` stamped into the manifests of the klibs produced for a module.
 *
 * The `unique_name` is the identity of a klib: consumers record it in the `depends` field of their own manifests,
 * so it must not change when a library migrates between Gradle and the Kotlin Toolchain (KTC-6014). KGP qualifies
 * it with the publishing group (`group:name`), and the toolchain must follow the same convention.
 */
@Tag("cli-test-group-publication")
class KlibNamingTest : CliTestBase() {

    @Test
    fun `klib unique_name is qualified with the publishing group`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("klib-naming"),
            "task",
            ":lib:compileLinuxX64Debug",
            ":lib:compileLinuxX64TestDebug",
            ":lib:compileJs",
            ":lib:compileJsTest",
            ":lib:compileMetadataCommon",
            ":lib:compileMetadataNative",
        )

        val nativeKlib = result.getTaskOutputPath(":lib:compileLinuxX64Debug") / "lib.klib"
        val nativeKlibManifest = nativeKlib.readKlibManifest()
        assertEquals("org.example:lib", nativeKlibManifest.getProperty("unique_name"))
        assertEquals("lib", nativeKlibManifest.getProperty("short_name"))
        // the 'short_name' and the file name are single-sourced from the short module name, like KGP's 'baseName'
        assertEquals(nativeKlib.nameWithoutExtension, nativeKlibManifest.getProperty("short_name"))

        val nativeTestKlib = result.getTaskOutputPath(":lib:compileLinuxX64TestDebug") / "lib_test.klib"
        val nativeTestKlibManifest = nativeTestKlib.readKlibManifest()
        assertEquals("org.example:lib_test", nativeTestKlibManifest.getProperty("unique_name"))
        assertEquals("lib_test", nativeTestKlibManifest.getProperty("short_name"))
        assertEquals(nativeTestKlib.nameWithoutExtension, nativeTestKlibManifest.getProperty("short_name"))

        val jsKlibManifest = (result.getTaskOutputPath(":lib:compileJs") / "lib.klib").readKlibManifest()
        assertEquals("org.example:lib", jsKlibManifest.getProperty("unique_name"))

        val jsTestKlibManifest = (result.getTaskOutputPath(":lib:compileJsTest") / "lib_test.klib").readKlibManifest()
        assertEquals("org.example:lib_test", jsTestKlibManifest.getProperty("unique_name"))

        // common 'lib_commonMain' metadata klib, compiled by the common metadata compiler (mixed platforms)
        val commonMetadataManifest = result.getTaskOutputPath(":lib:compileMetadataCommon").readKlibManifest()
        assertEquals("org.example:lib_commonMain", commonMetadataManifest.getProperty("unique_name"))

        // shared native 'lib_nativeMain' metadata klib, compiled by the Kotlin/Native compiler. The name uses the
        // source set name ('nativeMain'), not the fragment name ('native'): this is the name of the corresponding
        // entry in the all-metadata JAR, and what KGP stamps into its own shared-native metadata klibs.
        val nativeMetadataManifest = result.getTaskOutputPath(":lib:compileMetadataNative").readKlibManifest()
        assertEquals("org.example:lib_nativeMain", nativeMetadataManifest.getProperty("unique_name"))
        assertEquals("lib_nativeMain", nativeMetadataManifest.getProperty("short_name"))
    }

    @Test
    fun `JVM module name is qualified with the publishing group`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("klib-naming"),
            "task",
            ":lib:jarJvm",
            ":lib:compileJvmTest",
        )

        // Like KGP since Kotlin 2.4.0-Beta2, the JVM '-module-name' is 'org.example:lib'. The compiler derives the
        // '.kotlin_module' entry name from it, replacing the ':' forbidden in file names (KT-82216).
        ZipFile((result.getTaskOutputPath(":lib:jarJvm") / "lib-jvm.jar").toFile()).use { jar ->
            assertNotNull(
                jar.getEntry("META-INF/org.example_lib.kotlin_module"),
                "The jar should contain the .kotlin_module entry derived from the qualified module name, " +
                        "but its entries are: ${jar.entries().asSequence().map { it.name }.toList()}",
            )
        }

        val testKotlinModule = result.buildDir /
                "artifacts/CompiledJvmArtifact/libjvmTest/kotlin-output/META-INF/org.example_lib_test.kotlin_module"
        assertTrue(
            testKotlinModule.isRegularFile(),
            "The test compilation should use the qualified module name 'org.example:lib_test', " +
                    "so '$testKotlinModule' should exist",
        )
    }

    @Test
    fun `klib unique_name stays bare without a publishing group`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("klib-naming"),
            "task",
            ":lib-no-group:compileLinuxX64Debug",
            ":lib-no-group:compileJs",
            ":lib-no-group:jarJvm",
        )

        val nativeKlib = result.getTaskOutputPath(":lib-no-group:compileLinuxX64Debug") / "lib-no-group.klib"
        val nativeKlibManifest = nativeKlib.readKlibManifest()
        assertEquals("lib-no-group", nativeKlibManifest.getProperty("unique_name"))
        assertEquals("lib-no-group", nativeKlibManifest.getProperty("short_name"))
        // the 'short_name' and the file name are single-sourced from the short module name, like KGP's 'baseName'
        assertEquals(nativeKlib.nameWithoutExtension, nativeKlibManifest.getProperty("short_name"))

        val jsKlibManifest = (result.getTaskOutputPath(":lib-no-group:compileJs") / "lib-no-group.klib").readKlibManifest()
        assertEquals("lib-no-group", jsKlibManifest.getProperty("unique_name"))

        // without a publishing group, the JVM module name stays bare on all Kotlin versions (like KGP)
        ZipFile((result.getTaskOutputPath(":lib-no-group:jarJvm") / "lib-no-group-jvm.jar").toFile()).use { jar ->
            assertNotNull(
                jar.getEntry("META-INF/lib-no-group.kotlin_module"),
                "The jar should contain the .kotlin_module entry named after the bare module name, " +
                        "but its entries are: ${jar.entries().asSequence().map { it.name }.toList()}",
            )
        }
    }

    @Test
    fun `user-provided -Xir-module-name in freeCompilerArgs wins over the default klib name`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("klib-naming"),
            "task",
            ":lib-custom-ir-name:compileJs",
        )

        val jsKlibManifest =
            (result.getTaskOutputPath(":lib-custom-ir-name:compileJs") / "lib-custom-ir-name.klib").readKlibManifest()
        assertEquals("custom.explicit.name", jsKlibManifest.getProperty("unique_name"))

        // The option is single-valued: if Amper also passed its own -Xir-module-name, the compiler would use the last
        // value on the command line and print a strong warning about the duplicated argument. Amper must therefore
        // skip its own value entirely when the user provides one.
        result.assertStdoutDoesNotContain("is passed multiple times")
        result.assertStderrDoesNotContain("is passed multiple times")
    }

    /**
     * Reads the manifest of the klib at this path, which is either a packed klib file (a zip) or an unpacked klib
     * directory.
     */
    private fun Path.readKlibManifest(): Properties = if (isDirectory()) {
        Properties().apply { (this@readKlibManifest / "default" / "manifest").inputStream().use { load(it) } }
    } else {
        ZipFile(toFile()).use { zip ->
            val manifestEntry = zip.getEntry("default/manifest") ?: error("No 'default/manifest' entry in klib $this")
            Properties().apply { zip.getInputStream(manifestEntry).use { load(it) } }
        }
    }
}
