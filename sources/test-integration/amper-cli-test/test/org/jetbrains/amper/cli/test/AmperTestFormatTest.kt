/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.TestStdErr
import jetbrains.buildServer.messages.serviceMessages.TestStdOut
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.buildServiceMessages
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test

/**
 * TeamCity-encoded new line for the current OS.
 */
private val ENL = if (OS.current() == OS.WINDOWS) "|r|n" else "|n"

/**
 * New line character for the current OS.
 */
private val NL = System.lineSeparator()

class AmperTestFormatTest : AmperCliTestBase() {

    @Nested
    @DisplayName("JUnit tests")
    inner class JUnitTests {
        @Test
        fun `pretty CLI output format should be used by default`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("jvm-failed-test"),
                "test",
                assertEmptyStdErr = false,
                expectedExitCode = 1,
            )

            val expectedFailureOutput = """
            Started doTest()
            Passed doTest()
            Started stringComparisonFailure()
            Failed stringComparisonFailure()
                       => Exception: org.opentest4j.AssertionFailedError: Strings are not equal ==> expected: <EXPECTED_VALUE> but was: <ACTUAL_VALUE>
                            at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:158)
                            at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:139)
                            at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:201)
                            at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:184)
                            at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:1199)
                            at kotlin.test.junit5.JUnit5Asserter.assertEquals(JUnitSupport.kt:32)
                            at kotlin.test.AssertionsKt__AssertionsKt.assertEquals(Assertions.kt:63)
                            at kotlin.test.AssertionsKt.assertEquals(Unknown Source)
                            at FailedTest.stringComparisonFailure(tests.kt:18)
            Started booleanFailure()
            Failed booleanFailure()
                       => Exception: org.opentest4j.AssertionFailedError: The boolean value is incorrect
                            at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:42)
                            at org.junit.jupiter.api.Assertions.fail(Assertions.java:143)
                            at kotlin.test.junit5.JUnit5Asserter.fail(JUnitSupport.kt:56)
                            at kotlin.test.Asserter.assertTrue(Assertions.kt:766)
                            at kotlin.test.junit5.JUnit5Asserter.assertTrue(JUnitSupport.kt:30)
                            at kotlin.test.Asserter.assertTrue(Assertions.kt:776)
                            at kotlin.test.junit5.JUnit5Asserter.assertTrue(JUnitSupport.kt:30)
                            at kotlin.test.AssertionsKt__AssertionsKt.assertTrue(Assertions.kt:44)
                            at kotlin.test.AssertionsKt.assertTrue(Unknown Source)
                            at FailedTest.booleanFailure(tests.kt:13)
                            """.trimIndent()

            val outputPartToCheck = r.stdout
                .substringAfter("Started FailedTest")
                .substringBefore("Completed FailedTest")
                .trim()
            assertEqualsWithDiff(expectedFailureOutput.lines(), outputPartToCheck.lines(), "Output is incorrect")
        }

        @Test
        fun `test failure should print expected and actual values in teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("jvm-failed-test"),
                    "test",
                    "--format=teamcity",
                    assertEmptyStdErr = false,
                    expectedExitCode = 1,
                )

                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow("FailedTest", locationHint = "java:suite://FailedTest") {
                        testWithFlow(
                            name = "FailedTest: FailedTest.doTest()",
                            displayName = "doTest()",
                            locationHint = "java:test://FailedTest/doTest",
                        ) {}
                        testWithFlow(
                            name = "FailedTest: FailedTest.stringComparisonFailure()",
                            displayName = "stringComparisonFailure()",
                            locationHint = "java:test://FailedTest/stringComparisonFailure"
                        ) {
                            testFailed(
                                message = "org.opentest4j.AssertionFailedError: Strings are not equal ==> expected: <EXPECTED_VALUE> but was: <ACTUAL_VALUE>",
                                expectedValue = "EXPECTED_VALUE",
                                actualValue = "ACTUAL_VALUE",
                                serializedStackTrace = "org.opentest4j.AssertionFailedError: Strings are not equal ==> expected: <EXPECTED_VALUE> but was: <ACTUAL_VALUE>$ENL\tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:158)$ENL\tat org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:139)$ENL\tat org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:201)$ENL\tat org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:184)$ENL\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:1199)$ENL\tat kotlin.test.junit5.JUnit5Asserter.assertEquals(JUnitSupport.kt:32)$ENL\tat kotlin.test.AssertionsKt__AssertionsKt.assertEquals(Assertions.kt:63)$ENL\tat kotlin.test.AssertionsKt.assertEquals(Unknown Source)$ENL\tat FailedTest.stringComparisonFailure(tests.kt:18)$ENL"
                            )
                        }
                        testWithFlow(
                            name = "FailedTest: FailedTest.booleanFailure()",
                            displayName = "booleanFailure()",
                            locationHint = "java:test://FailedTest/booleanFailure"
                        ) {
                            testFailed(
                                message = "org.opentest4j.AssertionFailedError: The boolean value is incorrect",
                                serializedStackTrace = "org.opentest4j.AssertionFailedError: The boolean value is incorrect$ENL\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:42)$ENL\tat org.junit.jupiter.api.Assertions.fail(Assertions.java:143)$ENL\tat kotlin.test.junit5.JUnit5Asserter.fail(JUnitSupport.kt:56)$ENL\tat kotlin.test.Asserter.assertTrue(Assertions.kt:766)$ENL\tat kotlin.test.junit5.JUnit5Asserter.assertTrue(JUnitSupport.kt:30)$ENL\tat kotlin.test.Asserter.assertTrue(Assertions.kt:776)$ENL\tat kotlin.test.junit5.JUnit5Asserter.assertTrue(JUnitSupport.kt:30)$ENL\tat kotlin.test.AssertionsKt__AssertionsKt.assertTrue(Assertions.kt:44)$ENL\tat kotlin.test.AssertionsKt.assertTrue(Unknown Source)$ENL\tat FailedTest.booleanFailure(tests.kt:13)$ENL"
                            )
                        }
                    }
                }
                // TODO: JUnit Launcher has no way to disable summary in case of failures.
                //  Thus, we simply ignore unattributed messages for the sake of this test. Later, when we have our own
                //  launcher this filtering can be removed.
                val actualMessagesWithoutSummary = serviceMessages.filterNot {
                    (it is TestStdOut || it is TestStdErr) && it.testName.isBlank()
                }

                assertServiceMessagesEqual(expectedMessages, actualMessagesWithoutSummary,)
            }
        }

        @Test
        fun `junit 4 tests should print teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("multiplatform-tests"),
                    "test",
                    "-m",
                    "jvm-cli",
                    "--format=teamcity",
                    "--include-classes=com.example.jvmcli.OrderedTestSuite",
                    assertEmptyStdErr = false,
                )

                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow(
                        name = "com.example.jvmcli.OrderedTestSuite",
                        displayName = "OrderedTestSuite",
                        locationHint = "java:suite://com.example.jvmcli.OrderedTestSuite"
                    ) {
                        suiteWithFlow(
                            name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.JvmIntegrationTest",
                            displayName = "com.example.jvmcli.JvmIntegrationTest",
                            locationHint = "java:suite://com.example.jvmcli.JvmIntegrationTest"
                        ) {
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.JvmIntegrationTest: com.example.jvmcli.JvmIntegrationTest.integrationTest",
                                displayName = "integrationTest",
                                locationHint = "java:test://com.example.jvmcli.JvmIntegrationTest/integrationTest"
                            ) {
                                testStdOut("output line 1 in JvmIntegrationTest.integrationTest$NL")
                                testStdErr("error line 1 in JvmIntegrationTest.integrationTest$NL")
                                testStdOut("output line 2 in JvmIntegrationTest.integrationTest$NL")
                                testStdErr("error line 2 in JvmIntegrationTest.integrationTest$NL")
                            }
                        }
                        suiteWithFlow(
                            name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass1Test",
                            displayName = "com.example.jvmcli.MyClass1Test",
                            locationHint = "java:suite://com.example.jvmcli.MyClass1Test"
                        ) {
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass1Test: com.example.jvmcli.MyClass1Test.test1",
                                displayName = "test1",
                                locationHint = "java:test://com.example.jvmcli.MyClass1Test/test1"
                            ) {
                                testStdOut("running MyClass1Test.test1$NL")
                            }
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass1Test: com.example.jvmcli.MyClass1Test.test2",
                                displayName = "test2",
                                locationHint = "java:test://com.example.jvmcli.MyClass1Test/test2"
                            ) {
                                testStdOut("running MyClass1Test.test2$NL")
                            }
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass1Test: com.example.jvmcli.MyClass1Test.test3",
                                displayName = "test3",
                                locationHint = "java:test://com.example.jvmcli.MyClass1Test/test3"
                            ) {
                                testStdOut("running MyClass1Test.test3$NL")
                            }
                        }
                        suiteWithFlow(
                            name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass2Test",
                            displayName = "com.example.jvmcli.MyClass2Test",
                            locationHint = "java:suite://com.example.jvmcli.MyClass2Test"
                        ) {
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass2Test: com.example.jvmcli.MyClass2Test.test1",
                                displayName = "test1",
                                locationHint = "java:test://com.example.jvmcli.MyClass2Test/test1"
                            ) {
                                testStdOut("running MyClass2Test.test1$NL")
                            }
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass2Test: com.example.jvmcli.MyClass2Test.test2",
                                displayName = "test2",
                                locationHint = "java:test://com.example.jvmcli.MyClass2Test/test2"
                            ) {
                                testStdOut("running MyClass2Test.test2$NL")
                            }
                            testWithFlow(
                                name = "com.example.jvmcli.OrderedTestSuite: com.example.jvmcli.com.example.jvmcli.MyClass2Test: com.example.jvmcli.MyClass2Test.test3",
                                displayName = "test3",
                                locationHint = "java:test://com.example.jvmcli.MyClass2Test/test3"
                            ) {
                                testStdOut("running MyClass2Test.test3$NL")
                            }
                        }
                    }
                }
                assertServiceMessagesEqual(expectedMessages, serviceMessages,)
            }
        }

        @Test
        fun `output should be printed only once in TC format`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("multiplatform-tests"),
                    "test", "-m", "jvm-cli", "--format=teamcity", "--include-classes=com.example.jvmcli.MyClass1Test",
                    assertEmptyStdErr = false,
                )
                r.assertStdoutContains("running MyClass1Test.test1", expectedOccurrences = 1)
            }
        }

        @Test
        fun `junit 5 tests with params should print teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("jvm-tests-with-params"),
                    "test", "--format=teamcity",
                    assertEmptyStdErr = false,
                )

                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow(
                        name = "com.example.testswithparams.OverloadsTest",
                        displayName = "OverloadsTest",
                        locationHint = "java:suite://com.example.testswithparams.OverloadsTest"
                    ) {
                        testWithFlow(
                            name = "com.example.testswithparams.OverloadsTest: com.example.testswithparams.OverloadsTest.test()",
                            displayName = "test()",
                            locationHint = "java:test://com.example.testswithparams.OverloadsTest/test",
                        ) {
                            testStdOut("running OverloadsTest.test()$NL")
                        }
                        testWithFlow(
                            name = "com.example.testswithparams.OverloadsTest: com.example.testswithparams.OverloadsTest.test(TestInfo)",
                            displayName = "test(TestInfo)",
                            locationHint = "java:test://com.example.testswithparams.OverloadsTest/test[org.junit.jupiter.api.TestInfo]",
                        ) {
                            testStdOut("running OverloadsTest.test(TestInfo)$NL")
                        }
                        testWithFlow(
                            name = "com.example.testswithparams.OverloadsTest: com.example.testswithparams.OverloadsTest.test(TestInfo, TestReporter)",
                            displayName = "test(TestInfo, TestReporter)",
                            locationHint = "java:test://com.example.testswithparams.OverloadsTest/test[org.junit.jupiter.api.TestInfo, org.junit.jupiter.api.TestReporter]",
                        ) {
                            testStdOut("running OverloadsTest.test(TestInfo, TestReporter)$NL")
                        }
                    }
                }
                assertServiceMessagesEqual(expectedMessages, serviceMessages,)
            }
        }

        @Test
        fun `junit 5 dynamic tests should print teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("jvm-dynamic-tests"),
                    "test", "--format=teamcity",
                    assertEmptyStdErr = false,
                )
                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow("GeneratorTest", locationHint = "java:suite://GeneratorTest") {
                        suiteWithFlow(
                            name = "GeneratorTest: GeneratorTest.testFactory()",
                            displayName = "testFactory()",
                            locationHint = "java:test://GeneratorTest/testFactory",
                        ) {
                            testWithFlow(
                                name = "GeneratorTest: GeneratorTest.testFactory(): GeneratorTest.Generated number is 0",
                                displayName = "Generated number is 0",
                                locationHint = "java:test://GeneratorTest/testFactory",
                            ) {
                                testStdOut("running generated test with 0$NL")
                            }
                            testWithFlow(
                                name = "GeneratorTest: GeneratorTest.testFactory(): GeneratorTest.Generated number is 1",
                                displayName = "Generated number is 1",
                                locationHint = "java:test://GeneratorTest/testFactory",
                            ) {
                                testStdOut("running generated test with 1$NL")
                            }
                            testWithFlow(
                                name = "GeneratorTest: GeneratorTest.testFactory(): GeneratorTest.Generated number is 2",
                                displayName = "Generated number is 2",
                                locationHint = "java:test://GeneratorTest/testFactory",
                            ) {
                                testStdOut("running generated test with 2$NL")
                            }
                        }
                    }
                }
                assertServiceMessagesEqual(expectedMessages, serviceMessages,)
            }
        }

        @Test
        fun `junit 5 assumptions should print teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("jvm-aborted-tests"),
                    "test", "--format=teamcity",
                    assertEmptyStdErr = false,
                )
                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow("AbortedTest", locationHint = "java:suite://AbortedTest") {
                        testWithFlow(
                            name = "AbortedTest: AbortedTest.assumeWithoutMessage()",
                            displayName = "assumeWithoutMessage()",
                            locationHint = "java:test://AbortedTest/assumeWithoutMessage",
                        ) {
                            testStdOut("running assume without message$NL")
                            testIgnored("Assumption failed: assumption is not true")
                        }
                        testWithFlow(
                            name = "AbortedTest: AbortedTest.assumeWithMessage()",
                            displayName = "assumeWithMessage()",
                            locationHint = "java:test://AbortedTest/assumeWithMessage",
                        ) {
                            testStdOut("running assume with message$NL")
                            testIgnored("Assumption failed: 1 is not equal to 2 in this universe")
                        }
                    }
                }
                assertServiceMessagesEqual(expectedMessages, serviceMessages,)
            }
        }

        @Test
        fun `junit 5 ignore should print teamcity service messages`() {
            runSlowTest {
                val r = runCli(
                    projectDir = testProject("jvm-ignored-tests"),
                    "test", "--format=teamcity",
                    assertEmptyStdErr = false,
                )
                val serviceMessages = parseTeamCityServiceMessages(r.stdout)
                val expectedMessages = buildServiceMessages {
                    suiteWithFlow("IgnoredTest", locationHint = "java:suite://IgnoredTest") {
                        testWithFlow(
                            name = "IgnoredTest: IgnoredTest.ignoredWithoutMessage()",
                            displayName = "ignoredWithoutMessage()",
                            locationHint = "java:test://IgnoredTest/ignoredWithoutMessage",
                        ) {
                            testIgnored("public final void IgnoredTest.ignoredWithoutMessage() is @Disabled")
                        }
                        testWithFlow(
                            name = "IgnoredTest: IgnoredTest.ignoredWithMessage()",
                            displayName = "ignoredWithMessage()",
                            locationHint = "java:test://IgnoredTest/ignoredWithMessage",
                        ) {
                            testIgnored("Ignored for a reason")
                        }
                    }
                    suiteWithFlow("IgnoredSuiteTest", locationHint = "java:suite://IgnoredSuiteTest") {
                        testSuiteIgnored("Ignoring the suite")
                    }
                }
                assertServiceMessagesEqual(expectedMessages, serviceMessages,)
            }
        }
    }

    @Nested
    @DisplayName("Kotlin/Native tests")
    inner class KotlinNativeTests {
        @Test
        fun `pretty CLI output format should be used by default`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("native-tests"),
                "test",
                assertEmptyStdErr = false,
                expectedExitCode = 1,
            )
            val expectedFailureOutput = """
                Started MyTest
                Started testSucceed
                Passed testSucceed
                Started testFailure
                Failed testFailure
                           => Exception: kotlin.AssertionError: Expected <4>, actual <5>.
            """.trimIndent()
            r.assertStdoutContains(expectedFailureOutput)
            // Failure stack trace of the assertion error is heavily host-specific, so we're not checking it.
            r.assertStdoutContains("Completed MyTest")
        }

        @Test
        fun `test teamcity service messages`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("native-tests"),
                "test",
                "--format=teamcity",
                assertEmptyStdErr = false,
                expectedExitCode = 1,
            )
            val serviceMessages = parseTeamCityServiceMessages(r.stdout)
            val expectedMessages = buildServiceMessages {
                suiteWithFlow("MyTest", locationHint = "java:suite://MyTest") {
                    testWithFlow("testSucceed", locationHint = "java:test://MyTest/testSucceed") {}
                    testWithFlow("testFailure", locationHint = "java:test://MyTest/testFailure") {
                        testFailed("kotlin.AssertionError: Expected <4>, actual <5>.", serializedStackTrace = "SANITIZED")
                    }
                }
            }
            // We sanitize the stack trace because it's heavily host-specific
            assertServiceMessagesEqual(expectedMessages, serviceMessages, sanitizeStackTrace = true)
        }
    }

    @Nested
    @DisplayName("iOS Simulator tests")
    @MacOnly
    inner class IOSSimulatorTests {
        @Test
        fun `pretty CLI output format should be used by default`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("ios-simulator-tests"),
                "test",
                assertEmptyStdErr = false,
                expectedExitCode = 1,
            )
            val expectedFailureOutput = """
                Started MyTest
                Started testSucceed
                Passed testSucceed
                Started testFailure
                Failed testFailure
                           => Exception: kotlin.AssertionError: Expected <4>, actual <5>.
            """.trimIndent()
            r.assertStdoutContains(expectedFailureOutput)
            // Failure stack trace of the assertion error is heavily host-specific, so we're not checking it.
            r.assertStdoutContains("Completed MyTest")
        }

        @Test
        fun `test teamcity service messages`() = runSlowTest {
            val r = runCli(
                projectDir = testProject("ios-simulator-tests"),
                "test",
                "--format=teamcity",
                assertEmptyStdErr = false,
                expectedExitCode = 1,
            )
            val serviceMessages = parseTeamCityServiceMessages(r.stdout)
            val expectedMessages = buildServiceMessages {
                suiteWithFlow("MyTest", locationHint = "java:suite://MyTest") {
                    testWithFlow("testSucceed", locationHint = "java:test://MyTest/testSucceed") {}
                    testWithFlow("testFailure", locationHint = "java:test://MyTest/testFailure") {
                        testFailed("kotlin.AssertionError: Expected <4>, actual <5>.", serializedStackTrace = "SANITIZED")
                    }
                }
            }
            // We sanitize the stack trace because it's heavily host-specific
            assertServiceMessagesEqual(expectedMessages, serviceMessages, sanitizeStackTrace = true)
        }
    }
}

private fun parseTeamCityServiceMessages(text: String): List<ServiceMessage> = text.lines()
    .filter { it.startsWith("##teamcity[") }
    .mapNotNull { ServiceMessage.parse(it) }

private fun assertServiceMessagesEqual(
    expected: List<ServiceMessage>,
    actual: List<ServiceMessage>,
    sanitizeStackTrace: Boolean = false,
) {
    val expectedIdsMap = mutableMapOf<String, Int>()
    val actualIdsMap = mutableMapOf<String, Int>()
    assertEqualsWithDiff(
        expected.map { it.normalized(actualIdsMap, sanitizeStackTrace) },
        actual.map { it.normalized(expectedIdsMap, sanitizeStackTrace) },
    )
}

// The value of the flow IDs doesn't matter, what matters is that the links between different flows are preserved.
// Therefore, we can replace all flow IDs in a reproducible way to normalize the messages.
private fun ServiceMessage.normalized(normalizedFlowIds: MutableMap<String, Int>, sanitizeStackTrace: Boolean = false): String {
    val serializedMessage = asString()

    val flowIdRegex = Regex("flowId='([^']+)'")
    val parentRegex = Regex("parent='([^']+)'")
    val messageWithFlows = flowIdRegex.findAll(serializedMessage).fold(serializedMessage) { msg, match ->
        val flowId = match.groupValues[1]
        val normalized = normalizedFlowIds.computeIfAbsent(flowId) { normalizedFlowIds.size }
        msg
            .replace(flowIdRegex, "flowId='<normalized:$normalized>'")
            .replace(parentRegex, "parent='<normalized:$normalized>'")
    }
    return messageWithFlows
        .replace(Regex("timestamp='[^']+'"), "timestamp='<normalized>'")
        .replace(Regex("duration='[^']+'"), "duration='<normalized>'")
        .run {
            if (sanitizeStackTrace) replace(Regex("details='[^']+'"), "details='<normalized>'") else this
        }
}
