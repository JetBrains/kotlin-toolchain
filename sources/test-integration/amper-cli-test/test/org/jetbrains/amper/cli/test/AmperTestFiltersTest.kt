/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContainsLine
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class AmperTestFiltersTest : AmperCliTestBase() {

    @Test
    fun `include specific test (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test1",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
    }

    @Test
    fun `include specific test (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-test=com.example.shared.WorldTest.doTest",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific test with empty parentheses (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test1()",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
    }

    @Test
    fun `include specific test with empty parentheses (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-test=com.example.shared.WorldTest.doTest()",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific test in nested class (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-test=com.example.shared.EnclosingClass/NestedClass1.myNestedTest",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific test overload with 0 params (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
            "--include-test=com.example.testswithparams.OverloadsTest.test()",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running OverloadsTest.test()")
    }

    @Test
    fun `include specific test overload with 1 param (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
            "--include-test=com.example.testswithparams.OverloadsTest.test(org.junit.jupiter.api.TestInfo)",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running OverloadsTest.test(TestInfo)")
    }

    @Test
    fun `include specific test overload with 2 params (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
            "--include-test=com.example.testswithparams.OverloadsTest.test(org.junit.jupiter.api.TestInfo,org.junit.jupiter.api.TestReporter)",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running OverloadsTest.test(TestInfo, TestReporter)")
    }

    @Test
    fun `include specific test in nested class with nested parameter (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
            "--include-test=com.example.testswithparams.OverloadsTest/NestedTest.test(com.example.testswithparams.OverloadsTest/NestedArgument)",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running OverloadsTest.NestedTest.test(NestedArgument)")
    }

    @Test
    fun `run all tests with parameterized overloads (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
        )
        r.assertJUnitTestCount(expected = 4)
        r.assertStdoutContainsLine("running OverloadsTest.test()")
        r.assertStdoutContainsLine("running OverloadsTest.test(TestInfo)")
        r.assertStdoutContainsLine("running OverloadsTest.test(TestInfo, TestReporter)")
        r.assertStdoutContainsLine("running OverloadsTest.NestedTest.test(NestedArgument)")
    }

    @Test
    fun `include multiple specific tests (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test1",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
        )
        r.assertJUnitTestCount(expected = 2)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
    }

    @Test
    fun `include multiple specific tests (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-test=com.example.shared.WorldTest.doTest",
            "--include-test=com.example.shared.SharedIntegrationTest.integrationTest",
        )
        r.assertJUnitTestCount(expected = 2)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-classes=com.example.jvmcli.MyClass1Test",
        )
        r.assertJUnitTestCount(expected = 3)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
    }

    @Test
    fun `include specific class (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.WorldTest",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific nested class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("jvm-tests-with-params"),
            "test",
            "--include-classes=com.example.testswithparams.OverloadsTest/NestedTest",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running OverloadsTest.NestedTest.test(NestedArgument)")
    }

    @Test
    fun `include specific nested class (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.EnclosingClass/NestedClass1",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include multiple specific classes (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-classes=com.example.jvmcli.MyClass1Test",
            "--include-classes=com.example.jvmcli.MyClass2Test",
        )
        r.assertJUnitTestCount(expected = 6)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
        r.assertStdoutContainsLine("running MyClass2Test.test1")
        r.assertStdoutContainsLine("running MyClass2Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test3")
    }

    @Test
    fun `include class pattern (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-classes=com.example.jvmcli.MyClass*",
        )
        r.assertJUnitTestCount(expected = 6)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
        r.assertStdoutContainsLine("running MyClass2Test.test1")
        r.assertStdoutContainsLine("running MyClass2Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test3")
    }

    @Test
    fun `include class pattern (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.World*",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include class pattern across multiple modules`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "-m",
            "shared",
            "--include-classes=*IntegrationTest",
            assertEmptyStdErr = false, // some tests print to stderr
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("output line 1 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("output line 2 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 2)
        assertEquals(listOf(
            "error line 1 in JvmIntegrationTest.integrationTest",
            "error line 2 in JvmIntegrationTest.integrationTest",
        ), r.stderr.trim().lines())
    }

    @Test
    fun `include multiple class patterns (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-classes=com.example.jvmcli.*Integration*",
            "--include-classes=com.example.jvmcli.MyClass?Test",
            assertEmptyStdErr = false, // some tests print to stderr
        )
        r.assertJUnitTestCount(expected = 7)
        r.assertStdoutContainsLine("output line 1 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("output line 2 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
        r.assertStdoutContainsLine("running MyClass2Test.test1")
        r.assertStdoutContainsLine("running MyClass2Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test3")
        assertEquals(listOf(
            "error line 1 in JvmIntegrationTest.integrationTest",
            "error line 2 in JvmIntegrationTest.integrationTest",
        ), r.stderr.trim().lines())
    }

    @Test
    fun `include multiple class patterns (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.W?rld*",
            "--include-classes=com.example.shared.*Integration*",
        )
        r.assertJUnitTestCount(expected = 2)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include nested class pattern (shared)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.EnclosingClass/NestedClass*",
        )
        r.assertJUnitTestCount(expected = 2)
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass2.myNestedTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `exclude specific class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--exclude-classes=com.example.jvmcli.MyClass2Test",
            assertEmptyStdErr = false, // some tests print to stderr
        )
        r.assertJUnitTestCount(expected = 4)
        r.assertStdoutContainsLine("output line 1 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("output line 2 in JvmIntegrationTest.integrationTest")
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
        assertEquals(listOf(
            "error line 1 in JvmIntegrationTest.integrationTest",
            "error line 2 in JvmIntegrationTest.integrationTest",
        ), r.stderr.trim().lines())
    }

    @Test
    fun `include specific test and specific class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
            "--include-classes=com.example.jvmcli.MyClass2Test",
        )
        r.assertJUnitTestCount(expected = 4)
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test1")
        r.assertStdoutContainsLine("running MyClass2Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test3")
    }

    @Test
    fun `include class pattern and exclude exact class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-classes=com.example.jvmcli.MyClass*Test",
            "--exclude-classes=com.example.jvmcli.MyClass2Test",
        )
        r.assertJUnitTestCount(expected = 3)
        r.assertStdoutContainsLine("running MyClass1Test.test1")
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass1Test.test3")
    }

    @Test
    fun `include class pattern and exclude exact class (kmp)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "shared",
            "--include-classes=com.example.shared.*",
            "--exclude-classes=com.example.shared.SharedIntegrationTest",
        )
        r.assertJUnitTestCount(expected = 4)
        r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running EnclosingClass.enclosingClassTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 2) // jvm + current platform
        r.assertStdoutContainsLine("running EnclosingClass.NestedClass2.myNestedTest", nOccurrences = 2) // jvm + current platform
    }

    @Test
    fun `include specific test and include class pattern (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
            "--include-classes=com.example.jvmcli.MyClass2*",
        )
        r.assertJUnitTestCount(expected = 4)
        r.assertStdoutContainsLine("running MyClass1Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test1")
        r.assertStdoutContainsLine("running MyClass2Test.test2")
        r.assertStdoutContainsLine("running MyClass2Test.test3")
    }

    @Test
    fun `include specific test and exclude other specific class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
            "--exclude-classes=com.example.jvmcli.MyClass2Test",
        )
        r.assertJUnitTestCount(expected = 1)
        r.assertStdoutContainsLine("running MyClass1Test.test2")
    }

    @Test
    fun `include specific test and exclude containing specific class (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
            "--exclude-classes=com.example.jvmcli.MyClass1Test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        r.assertJUnitTestCount(expected = 0)
    }

    @Test
    fun `include specific test and exclude containing class via pattern (jvm)`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-tests"),
            "test",
            "-m",
            "jvm-cli",
            "--include-test=com.example.jvmcli.MyClass1Test.test2",
            "--exclude-classes=com.example.jvmcli.MyClass?Test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        r.assertJUnitTestCount(expected = 0)
    }

    @Test
    fun `exclude module (jvm)`() = runSlowTest {
        val projectRoot = testProject("jvm-multimodule-tests")
        val result = runCli(projectDir = projectRoot, "test")

        // all tests run
        result.assertStdoutContains("Hello from test 1")
        result.assertStdoutContains("Hello from test 2")

        // tests from module 1 aren't run
        val result2 = runCli(projectDir = projectRoot, "test", "--exclude-module=one")
        result2.assertStdoutDoesNotContain("Hello from test 1")
        result2.assertStdoutContains("Hello from test 2")
    }

    private val junitTestCountRegex = Regex("""\[\s*(?<count>\d+) tests found\s*]""")

    private fun AmperCliResult.assertJUnitTestCount(expected: Int) {
        val countMatch = stdout.lines().firstNotNullOfOrNull { junitTestCountRegex.matchEntire(it.trim()) }
            ?: fail("JUnit test count not present in stdout: $stdout")
        val count = countMatch.groups["count"]?.value?.toIntOrNull()
            ?: fail("JUnit test count could not be parsed: ${countMatch.groups}")
        assertEquals(expected, count, "Expected $expected 'found' JVM tests but got $count")
    }

}
