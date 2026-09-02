/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compilerplugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertContainsRelativeFiles
import org.jetbrains.amper.cli.test.utils.assertSomeStderrLineContains
import org.jetbrains.amper.cli.test.utils.assertSomeStdoutLineContains
import org.jetbrains.amper.cli.test.utils.assertStderrDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.runTestWithMdc
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class KspTest: CliTestBase() {

    @Test
    fun `ksp processor exception fails build`() = runTestWithMdc(timeout = 1.minutes) {
        val projectRoot = testProject("ksp-processor-exception")
        val buildResult = runCli(projectRoot, "build", expectedExitCode = 1, assertEmptyStdErr = false)

        buildResult.assertSomeStderrLineContains("intentional KSP processor failure")
        buildResult.assertSomeStderrLineContains("KSP execution failed with exit code 1")
    }

    @Test
    fun `ksp jvm invalid kotlin version`() = runSlowTest {
        val projectRoot = testProject("ksp-jvm-invalid-kotlin-version")
        val buildResult = runCli(projectRoot, "build", expectedExitCode = 1, assertEmptyStdErr = false)

        // KSP shouldn't crash
        buildResult.assertStderrDoesNotContain("Internal error")
        buildResult.assertSomeStderrLineContains("Invalid Kotlin compiler version `2`. Should be in the format `x.y.*`.")
    }

    @Test
    fun `ksp jvm autoservice`() = runSlowTest {
        val projectRoot = testProject("ksp-jvm-autoservice")
        val buildResult = runCli(projectRoot, "build")

        val generatedFilesDir = buildResult.generatedFilesDir(module = "service-impl", fragment = "main")
        generatedFilesDir.assertContainsRelativeFiles(
            "resources/ksp/META-INF/services/com.sample.service.MyService",
        )

        val runResult = runCli(projectRoot, "run")
        runResult.assertSomeStdoutLineContains("Hello, service!")
    }

    @Test
    fun `ksp jvm local processor`() = runSlowTest {
        val projectRoot = testProject("ksp-jvm-local-processor")
        val buildResult = runCli(projectRoot, "build")

        val generatedFilesDir = buildResult.generatedFilesDir(module = "consumer", fragment = "main")
        generatedFilesDir.assertContainsRelativeFiles(
            "resources/ksp/com/sample/generated/annotated-classes.txt",
        )

        val runResult = runCli(projectRoot, "run")
        runResult.assertStdoutContains("""
            My annotated classes are:
            org.sample.ksp.localprocessor.consumer.B
            org.sample.ksp.localprocessor.consumer.A
        """.trimIndent())
    }

    @Test
    fun `ksp kmp local processor`() = runSlowTest {
        val projectRoot = testProject("ksp-kmp-local-processor")
        val buildResult = runCli(projectRoot, "build", configureAndroidHome = true)

        buildResult.generatedFilesDir(module = "consumer", fragment = "jvm").assertContainsRelativeFiles(
            "classes/ksp/com/sample/myprocessor/gen/MyGeneratedClass.class",
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/java/com/sample/myprocessor/gen/MyCommonClassGeneratedJava.java",
            "src/ksp/java/com/sample/myprocessor/gen/MyJvmClassGeneratedJava.java",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyJvmClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "android").assertContainsRelativeFiles(
            "classes/ksp/com/sample/myprocessor/gen/MyGeneratedClass.class",
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/java/com/sample/myprocessor/gen/MyAndroidClassGeneratedJava.java",
            "src/ksp/java/com/sample/myprocessor/gen/MyCommonClassGeneratedJava.java",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyAndroidClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "mingwX64").assertContainsRelativeFiles(
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyMingwClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyMingwX64ClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyNativeClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "linuxX64").assertContainsRelativeFiles(
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyLinuxX64ClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyNativeClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "js").assertContainsRelativeFiles(
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyJsClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyWebClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "wasmJs").assertContainsRelativeFiles(
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyWasmJsClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyWebClassGenerated.kt",
        )

        buildResult.generatedFilesDir(module = "consumer", fragment = "wasmWasi").assertContainsRelativeFiles(
            "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
            "src/ksp/kotlin/com/sample/myprocessor/gen/MyWasmWasiClassGenerated.kt",
        )

        if (OsFamily.current.isMac) {
            buildResult.generatedFilesDir(module = "consumer", fragment = "iosArm64").assertContainsRelativeFiles(
                "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyAppleClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyIosArm64ClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyIosClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyNativeClassGenerated.kt",
            )
            buildResult.generatedFilesDir(module = "consumer", fragment = "iosSimulatorArm64").assertContainsRelativeFiles(
                "resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyAppleClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyCommonClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyIosClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyIosSimulatorArm64ClassGenerated.kt",
                "src/ksp/kotlin/com/sample/myprocessor/gen/MyNativeClassGenerated.kt",
            )
        }

        fun generatedResourceFor(fragment: String) =
            buildResult.generatedFilesDir(module = "consumer", fragment = fragment)
                .resolve("resources/ksp/com/sample/myprocessor/gen/annotated-classes.txt")

        generatedResourceFor(fragment = "jvm").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyJvmClass
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())
        generatedResourceFor(fragment = "android").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyCommonClass
            com.sample.ksp.localprocessor.consumer.MyAndroidClass
        """.trimIndent())
        generatedResourceFor(fragment = "js").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyWebClass
            com.sample.ksp.localprocessor.consumer.MyJsClass
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())
        generatedResourceFor(fragment = "wasmJs").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyWebClass
            com.sample.ksp.localprocessor.consumer.MyWasmJsClass
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())
        generatedResourceFor(fragment = "wasmWasi").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyWasmWasiClass
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())
        // mingw (and not mingwX64) because of how we collapse fragments right now
        generatedResourceFor(fragment = "mingwX64").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyNativeClass
            com.sample.ksp.localprocessor.consumer.MyMingwX64Class
            com.sample.ksp.localprocessor.consumer.MyMingwClass
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())
        generatedResourceFor(fragment = "linuxX64").assertContentEquals("""
            com.sample.ksp.localprocessor.consumer.MyNativeClass
            com.sample.ksp.localprocessor.consumer.MyLinuxX64Class
            com.sample.ksp.localprocessor.consumer.MyCommonClass
        """.trimIndent())

        if (OsFamily.current.isMac) {
            generatedResourceFor(fragment = "iosArm64").assertContentEquals("""
                com.sample.ksp.localprocessor.consumer.MyNativeClass
                com.sample.ksp.localprocessor.consumer.MyIosClass
                com.sample.ksp.localprocessor.consumer.MyIosArm64Class
                com.sample.ksp.localprocessor.consumer.MyCommonClass
                com.sample.ksp.localprocessor.consumer.MyAppleClass
            """.trimIndent())
            generatedResourceFor(fragment = "iosSimulatorArm64").assertContentEquals("""
                com.sample.ksp.localprocessor.consumer.MyNativeClass
                com.sample.ksp.localprocessor.consumer.MyIosSimulatorArm64Class
                com.sample.ksp.localprocessor.consumer.MyIosClass
                com.sample.ksp.localprocessor.consumer.MyCommonClass
                com.sample.ksp.localprocessor.consumer.MyAppleClass
            """.trimIndent())
        }
    }

    @Test
    fun `ksp jvm dagger`() = runSlowTest {
        val projectRoot = testProject("ksp-jvm-dagger")
        val buildResult = runCli(projectRoot, "build")

        val generatedFilesDir = buildResult.generatedFilesDir(module = "ksp-jvm-dagger", fragment = "main")
        generatedFilesDir.assertContainsRelativeFiles(
            "src/ksp/java/com/sample/dagger/CoffeeMaker_Factory.java",
            "src/ksp/java/com/sample/dagger/DaggerCoffeeShop.java",
            "src/ksp/java/com/sample/dagger/HeaterModule_Companion_ProvideHeaterFactory.java",
            "src/ksp/java/com/sample/dagger/Heater_Factory.java",
        )

        val runResult = runCli(projectRoot, "run")
        runResult.assertSomeStdoutLineContains("Heater: heating...")
        runResult.assertSomeStdoutLineContains("CoffeeMaker: brewing...")
    }

    @Test
    fun `ksp jvm dagger with catalog refs`() = runSlowTest {
        val projectRoot = testProject("ksp-jvm-dagger-catalog")
        val buildResult = runCli(projectRoot, "build")

        val generatedFilesDir = buildResult.generatedFilesDir(module = "ksp-jvm-dagger-catalog", fragment = "main")
        generatedFilesDir.assertContainsRelativeFiles(
            "src/ksp/java/com/sample/dagger/CoffeeMaker_Factory.java",
            "src/ksp/java/com/sample/dagger/DaggerCoffeeShop.java",
            "src/ksp/java/com/sample/dagger/HeaterModule_Companion_ProvideHeaterFactory.java",
            "src/ksp/java/com/sample/dagger/Heater_Factory.java",
        )

        val runResult = runCli(projectRoot, "run")
        runResult.assertSomeStdoutLineContains("Heater: heating...")
        runResult.assertSomeStdoutLineContains("CoffeeMaker: brewing...")
    }

    @Test
    fun `ksp android room`() = runSlowTest {
        val projectRoot = testProject("ksp-android-room")
        val generatedSchemaPath = projectRoot / "generated-db-schema"
        generatedSchemaPath.deleteRecursively()

        val buildResult = runCli(projectRoot, "build", configureAndroidHome = true)

        val generatedFilesDir = buildResult.generatedFilesDir(module = "ksp-android-room", fragment = "main")

        generatedFilesDir.assertContainsRelativeFiles(
            "src/ksp/kotlin/com/jetbrains/sample/app/AppDatabase_Impl.kt",
            "src/ksp/kotlin/com/jetbrains/sample/app/UserDao_Impl.kt",
        )
        generatedSchemaPath.assertContainsRelativeFiles(
            "com.jetbrains.sample.app.AppDatabase/1.json",
        )
    }

    @Test
    fun `ksp android test-scope aar dependency`() = runSlowTest {
        val projectRoot = testProject("ksp-android-test-aar-dependency")
        val generatedSchemaPath = projectRoot / "generated-db-schema"
        generatedSchemaPath.deleteRecursively()

        val buildResult = runCli(projectRoot, "build", configureAndroidHome = true)

        val module = "ksp-android-test-aar-dependency"
        buildResult.generatedFilesDir(module = module, fragment = "jvmTest").assertContainsRelativeFiles(
            "src/ksp/kotlin/TestDatabaseConstructor.kt",
            "src/ksp/kotlin/TestDatabase_Impl.kt",
            "src/ksp/kotlin/TodoDao_Impl.kt",
        )
        // The Android variant of the Room runtime is an AAR, and it is only in the test dependencies, so the
        // extracted classes must come from the test-scope AAR transform task (see KTC-5649).
        buildResult.generatedFilesDir(module = module, fragment = "androidTest").assertContainsRelativeFiles(
            "src/ksp/kotlin/TestDatabaseConstructor.kt",
            "src/ksp/kotlin/TestDatabase_Impl.kt",
            "src/ksp/kotlin/TodoDao_Impl.kt",
        )
        generatedSchemaPath.assertContainsRelativeFiles(
            "android/TestDatabase/1.json",
            "jvm/TestDatabase/1.json",
        )
    }

    @Test
    fun `compose multiplatform room`() = runSlowTest {
        val projectRoot = testProject("compose-multiplatform-room")
        val generatedSchemaPath = projectRoot / "shared/generated-db-schema"
        generatedSchemaPath.deleteRecursively()

        val buildResult = runCli(
            projectRoot,
            "build",
            configureAndroidHome = true,
        )

        buildResult.generatedFilesDir(module = "shared", fragment = "android").assertContainsRelativeFiles(
            "src/ksp/kotlin/AppDatabaseConstructor.kt",
            "src/ksp/kotlin/AppDatabase_Impl.kt",
            "src/ksp/kotlin/TodoDao_Impl.kt",
        )
        buildResult.generatedFilesDir(module = "shared", fragment = "jvm").assertContainsRelativeFiles(
            "src/ksp/kotlin/AppDatabaseConstructor.kt",
            "src/ksp/kotlin/AppDatabase_Impl.kt",
            "src/ksp/kotlin/TodoDao_Impl.kt",
        )

        if (SystemInfo.CurrentHost.family.isMac) {
            buildResult.generatedFilesDir(module = "shared", fragment = "iosSimulatorArm64").assertContainsRelativeFiles(
                "src/ksp/kotlin/AppDatabaseConstructor.kt",
                "src/ksp/kotlin/AppDatabase_Impl.kt",
                "src/ksp/kotlin/TodoDao_Impl.kt",
            )
            generatedSchemaPath.assertContainsRelativeFiles(
                "android/AppDatabase/1.json",
                "ios/AppDatabase/1.json",
                "jvm/AppDatabase/1.json",
            )
        } else {
            generatedSchemaPath.assertContainsRelativeFiles(
                "android/AppDatabase/1.json",
                "jvm/AppDatabase/1.json",
            )
        }
    }

    private fun AmperCliResult.generatedFilesDir(module: String, fragment: String): Path =
        buildDir / "generated" / module / fragment
}

private fun Path.assertContentEquals(expectedContents: String) {
    assertTrue(exists(), "Expected file $pathString to exist")
    assertTrue(isRegularFile(), "Expected a file but got a directory: $pathString")
    assertEquals(expectedContents, readText())
}
