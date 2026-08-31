/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContainsLine
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class AmperTestFiltersTest : AmperCliTestBase() {

    @Nested
    inner class SpecificTest {
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
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3) // jvm + wasmJs + host platform
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
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3) // jvm + wasmJs + host platform
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
            // jvm + wasmJs + host platform = 3 occurrences
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 3)
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
            // jvm + wasmJs + host platform = 3 occurrences
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 3)
        }
    }

    @Nested
    inner class SpecificClass {
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
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3) // jvm + wasmJs + host platform
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
            // jvm + wasmJs + host platform = 3 occurrences
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 3)
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
    }

    @Nested
    inner class ClassPattern {
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
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3) // jvm + wasmJs + host platform
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
            r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 3)
            assertEquals(
                listOf(
                    "error line 1 in JvmIntegrationTest.integrationTest",
                    "error line 2 in JvmIntegrationTest.integrationTest",
                ), r.stderr.trim().lines()
            )
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
            assertEquals(
                listOf(
                    "error line 1 in JvmIntegrationTest.integrationTest",
                    "error line 2 in JvmIntegrationTest.integrationTest",
                ), r.stderr.trim().lines()
            )
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
            // jvm + wasmJs + host platform = 3 occurrences
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running SharedIntegrationTest.integrationTest", nOccurrences = 3)
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
            // jvm + wasmJs + host platform = 3 occurrences
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass2.myNestedTest", nOccurrences = 3)
        }
    }

    @Nested
    inner class Combinations {
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
            r.assertJUnitTestCount(expected = 6)
            // jvm + wasmJs + current host platform = 3 occurrences
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running EnclosingClass.enclosingClassTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass1.myNestedTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running EnclosingClass.NestedClass2.myNestedTest", nOccurrences = 3)
            r.assertStdoutContainsLine("running JvmTaggedTest.slowJvmTest") // jvm-only test
            r.assertStdoutContainsLine("running JvmTaggedTest.fastJvmTest") // jvm-only test
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
    }

    @Nested
    inner class Tags {
        @Test
        fun `include single tag (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-tag=slow",
            )
            r.assertJUnitTestCount(expected = 3)
            r.assertStdoutContainsLine("running TaggedTest.slowTest")
            r.assertStdoutContainsLine("running TaggedTest.slowFlakyTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.slowTest")
        }

        @Test
        fun `include multiple tags (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-tag=fast",
                "--include-tag=flaky",
            )
            r.assertJUnitTestCount(expected = 3)
            r.assertStdoutContainsLine("running TaggedTest.fastTest")
            r.assertStdoutContainsLine("running TaggedTest.slowFlakyTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.fastTest")
        }

        @Test
        fun `include tag expression (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-tag=slow & !flaky",
            )
            r.assertJUnitTestCount(expected = 2)
            r.assertStdoutContainsLine("running TaggedTest.slowTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.slowTest")
        }

        @Test
        fun `exclude tag single (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--exclude-tag=slow",
            )
            r.assertJUnitTestCount(expected = 4)
            r.assertStdoutContainsLine("running TaggedTest.fastTest")
            r.assertStdoutContainsLine("running TaggedTest.untaggedTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.fastTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.untaggedTest")
        }

        @Test
        fun `include tag and exclude tag (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-tag=slow",
                "--exclude-tag=flaky",
            )
            r.assertJUnitTestCount(expected = 2)
            r.assertStdoutContainsLine("running TaggedTest.slowTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.slowTest")
        }

        @Test
        fun `include tag and include specific class (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-classes=com.example.taggedtests.TaggedTest",
                "--include-tag=fast",
            )
            // the tag filter matches tests in both classes, but only the given class is included
            r.assertJUnitTestCount(expected = 1)
            r.assertStdoutContainsLine("running TaggedTest.fastTest")
        }

        @Test
        fun `include tag and include class pattern matching multiple classes (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-classes=com.example.taggedtests.*TaggedTest",
                "--include-tag=slow",
            )
            r.assertJUnitTestCount(expected = 3)
            r.assertStdoutContainsLine("running TaggedTest.slowTest")
            r.assertStdoutContainsLine("running TaggedTest.slowFlakyTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.slowTest")
        }

        @Test
        fun `include tag and include multiple specific classes (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-classes=com.example.taggedtests.TaggedTest",
                "--include-classes=com.example.taggedtests.OtherTaggedTest",
                "--include-tag=fast",
            )
            r.assertJUnitTestCount(expected = 2)
            r.assertStdoutContainsLine("running TaggedTest.fastTest")
            r.assertStdoutContainsLine("running OtherTaggedTest.fastTest")
        }

        @Test
        fun `include tag matching only tests in a non-included class (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                // the 'flaky' tag only exists in TaggedTest, so no test matches both filters
                "--include-classes=com.example.taggedtests.OtherTaggedTest",
                "--include-tag=flaky",
                expectedExitCode = 1, // JUnit fails when no test is discovered
                assertEmptyStdErr = false,
            )
            r.assertJUnitTestCount(expected = 0)
            r.assertStdoutDoesNotContain("running TaggedTest.slowFlakyTest")
        }

        @Test
        fun `include tag and exclude specific class (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--exclude-classes=com.example.taggedtests.TaggedTest",
                "--include-tag=slow",
            )
            r.assertJUnitTestCount(expected = 1)
            r.assertStdoutContainsLine("running OtherTaggedTest.slowTest")
        }

        @Test
        fun `invalid tag expression is rejected (jvm)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-tagged-tests"),
                "test",
                "--include-tag=slow &",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
            )
            r.assertStderrContains("invalid tag expression 'slow &'")
        }

        @Test
        fun `tag filters matching untagged tests still run native tests (kmp)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("multiplatform-tests"),
                "test",
                "-m",
                "shared",
                // Kotlin/Native and Kotlin/Wasm tests are all untagged, so they match this filter and must be run
                "--exclude-tag=slow",
            )
            r.assertStdoutContainsLine("running WorldTest.doTest", nOccurrences = 3) // jvm + wasmJs + host platform
            r.assertStdoutContainsLine("running JvmTaggedTest.fastJvmTest") // jvm-only test
            r.assertStdoutDoesNotContain("running JvmTaggedTest.slowJvmTest")
        }

        @Test
        fun `tag filters matching only jvm tests skip native tests (kmp)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("multiplatform-tests"),
                "test",
                "-m",
                "shared",
                // Kotlin/Native tests are all untagged, so none of them can match this filter, but a JVM test does
                "--include-tag=slow",
            )
            r.assertJUnitTestCount(expected = 1)
            r.assertStdoutContainsLine("running JvmTaggedTest.slowJvmTest")
            // the native test executable should not have been run at all
            r.assertStdoutDoesNotContain("running WorldTest.doTest")
        }

        @Test
        fun `tag filters matching no test at all skip native tests (kmp)`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("multiplatform-tests"),
                "test",
                "-m",
                "shared",
                // no test has this tag, and Kotlin/Native tests are all untagged, so nothing can match this filter
                "--include-tag=flaky",
                expectedExitCode = 1, // no JVM test matches either, and JUnit fails when no test is discovered
                assertEmptyStdErr = false,
            )
            r.assertJUnitTestCount(expected = 0)
            // the native test executable should not have been run at all
            r.assertStdoutDoesNotContain("running WorldTest.doTest")
        }
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
