/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertTextFileContentEqualsIgnoringLineEndings
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertTrue

// todo (AB) : [AMPER-721]
//  - add cinterop (direct and in refined fragment) to check that
//    commonized cinterop klibs are added as an input to the native metadata compilation
class KotlinModuleMetadataTest : AmperCliTestBase() {

    val testGoldenFilesRoot: Path = Dirs.amperSourcesRoot.resolve("test-integration/amper-cli-test/testResources/metadata")

    /**
     * This test checks that metadata compilation of common fragment of a multiplatform module
     * that targets mixed native and non-native platforms
     * is successful.
     */
    @Test
    fun `run metadata compilation of mixed native and non-native common fragment`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":library:compileMetadataCommon",
            assertEmptyStdErr = true)
    }

    /**
     * This test checks that metadata compilation correctly processes expected/actual fragment refinement.
     *
     * Test performs metadata compilation for the 'linux' fragment of the multiplatform native-only module.
     * It differs from the previous test that checks common compilation, because
     * 'linux' fragment refines other fragments within the same module ('common', 'native')
     * and provides actuals for expected declaration there.
     * So metadata-compilation should be called with parameter `-Xrefines-paths` initialized properly.
     */
    @Test
    fun `run metadata compilation of mixed native and non-native intermediate linux fragment`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":library:compileMetadataLinux",
            assertEmptyStdErr = true)
    }

    /**
     * This test checks that metadata compilation of a multiplatform native-only module is successful.
     * The module depends on:
     *  - Another local shared module ('library'),
     *  - KMP library ('kotlinx-coroutines-core')
     */
    @Test
    fun `run metadata compilation of native only common fragment with dependencies`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":nativeShared:compileMetadataCommon",
            assertEmptyStdErr = true)
    }

    /**
     * This test checks that metadata compilation of a multiplatform native-only module is successful.
     *
     * Test performs metadata compilation for the 'linux' fragment.
     *
     * 'linux' fragment depends on:
     *  - KMP library with cinterop source sets applicable to the fragment ('crypto-rand')
     *  - Another local shared module ('library'),
     *  - Other fragments within the same module ('common', 'native')
     *  - KMP library ('kotlinx-coroutines-core')
     */
    @Test
    fun `run metadata compilation of native only intermediate fragment with cinterop dependencies`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":nativeShared:compileMetadataLinux",
            assertEmptyStdErr = true)
    }

    /**
     * This test checks that metadata compilation of a multiplatform native-only shared fragment
     * that uses commonized platform API is successful.
     */
    @Test
    fun `run native metadata compilation platform API`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":nativePlatform:compileMetadataCommon",
            assertEmptyStdErr = false
        )

        assertTrue(
            actual = r.stderr.replace("ERROR logging: using Kotlin home directory dist\\kotlinc", "").isBlank(),
            message = """
                    Process stderr must be empty for the Kotlin CLI call (PID ${r.pid}):
                    "kotlin task :nativePlatform:compileMetadataCommon",
                    Kotlin Toolchain STDERR:
                    ${r.stderr.prependIndent("                    ")}
                """.trimMargin(),
        )
    }

    /**
     * This test checks that metadata compilation of a multiplatform native-only module
     * depending on another exported module transitively is successful.
     *
     * Test performs metadata compilation for the 'linux' fragment of module 'linuxWindowsShared'.
     * That fragment uses symbols defined in the common fragment of the 'library' module.
     * Module 'linuxWindowsShared' depends on 'library' transitively via exported dependency declared in the module 'nativeShared'
     */
    @Test
    fun `run metadata compilation of linux intermediate fragment depending on another exported local module`() = runSlowTest {
        val r = runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":linuxWindowsShared:compileMetadataLinux",
            assertEmptyStdErr = true)
    }

    /**
     * This checks that assembleMetadata task creates a correct kotlin project descriptor (kotlin-project-structure-metadata.json)
     *
     * Important: gold file descriptor was created by the Gradle project that contains a module with the same configuration
     * as the module 'libraryNested' from the test Kotlin Toolchain project.
     * If the test fails for some reason, it means that most probably there is a bug in the Kotlin Toolchain code,
     * not the issue with the golden file.
     * To ensure the compatibility with the KGP consumer, the golden file should be kept unchanged.
     */
    @Test
    fun `assemble kotlin project structure descriptor of libraryNested module`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":libraryNested:assembleMetadata",
        )

        assertTextFileContentEqualsIgnoringLineEndings(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.kotlin-project-structure-metadata.json"),
            tempRoot / "build" / "tasks" / "_libraryNested_assembleMetadata" / "kotlin-project-structure-metadata.json"
        )

        // todo (AB) : [AMPER-721] Check
        //  -metadata and -sources files existence
        //  -metadata and -sources content
    }

    // todo (AB): [AMPER-721] check PSM/Gradle metadata generation for edge cases
}


