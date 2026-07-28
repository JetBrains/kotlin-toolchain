/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.event

import org.junit.jupiter.api.assertThrows
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class JUnitEventProtocolTest {
    @Test
    fun `serialize-deserialize every event type`() {
        val text = "Expected: café\r\nActual:\n| [] \\ 😀"
        val events = [
            JUnitEventProtocol.Event.TestStdout("test|1", text),
            JUnitEventProtocol.Event.TestStderr(null, text),
            JUnitEventProtocol.Event.SuiteStarted(
                id = "suite",
                parentId = "parentSuite",
                displayName = "Suite | 😀",
                location = JUnitEventProtocol.Location.Class("pkg.Suite"),
                teamCityName = "pkg.Suite",
            ),
            JUnitEventProtocol.Event.TestStarted(
                id = "test",
                parentId = "suite",
                displayName = "test()",
                location = JUnitEventProtocol.Location.Method("pkg.Suite", "test", ["java.lang.String", "😀"]),
                teamCityName = "pkg.Suite.test()",
            ),
            JUnitEventProtocol.Event.SuiteFinished(id = "suite", description = "because | café"),
            JUnitEventProtocol.Event.Succeeded(id = "test", durationMillis = 123),
            JUnitEventProtocol.Event.Skipped(id = "test", durationMillis = 0, description = "not applicable"),
            JUnitEventProtocol.Event.Failed(
                id = "test",
                durationMillis = 42,
                failureMessage = text,
                stackTrace = "java.lang.AssertionError\\n\\tat SampleTest.kt:42",
                expected = "expected",
                actual = "actual",
                expectedFilePath = Path("/tmp/expected/file.txt"),
                actualFilePath = Path("/tmp/actual/file.txt"),
            ),
            JUnitEventProtocol.Event.Report(
                id = "test",
                key = "key",
                value = text,
                mediaType = "text/plain; charset=utf-8",
                timestampMillis = 1_234_567_890L,
            ),
        ]

        events.forEach { event ->
            assertEquals(event, JUnitEventProtocol.decode(JUnitEventProtocol.encode(event)), event.toString())
        }
        val uriLocation = JUnitEventProtocol.Event.TestStarted(
            "test",
            null,
            "dynamic",
            JUnitEventProtocol.Location.Uri("file:///tmp/café|😀"),
        )
        assertEquals(uriLocation, JUnitEventProtocol.decode(JUnitEventProtocol.encode(uriLocation)))
    }

    @Test
    fun `sensitive characters are properly escaped`() {
        assertEquals(
            "@@ktc-junit:testStdout|id:test\\p1|text:line 1\\r\\nline 2\\nvalue\\: \\p \\\\",
            JUnitEventProtocol.encode(
                JUnitEventProtocol.Event.TestStdout("test|1", "line 1\r\nline 2\nvalue: | \\")
            )
        )
    }

    @Test
    fun `optional values are omitted and properly deserialized`() {
        val event = JUnitEventProtocol.Event.Failed(
            id = "test",
            durationMillis = null,
            failureMessage = "failure",
            stackTrace = null,
            expected = null,
            actual = null,
            expectedFilePath = null,
            actualFilePath = null,
        )

        val encoded = JUnitEventProtocol.encode(event)
        assertFalse(encoded.contains("actual"))
        assertEquals(event, JUnitEventProtocol.decode(encoded))
    }

    @Test
    fun `non-protocol output is ignored`() {
        assertNull(JUnitEventProtocol.decode("normal test output"))
    }

    @Test
    fun `message without a type is rejected`() {
        assertMalformed("@@ktc-junit:")
    }

    @Test
    fun `message with an unknown type is rejected`() {
        assertMalformed("@@ktc-junit:unknown")
    }

    @Test
    fun `message missing a required field is rejected`() {
        assertMalformed("@@ktc-junit:testStarted|id:test")
    }

    @Test
    fun `message with an unknown field is rejected`() {
        assertMalformed("@@ktc-junit:testStarted|id:test|unknown:value")
    }

    @Test
    fun `message with an invalid escape sequence is rejected`() {
        assertMalformed("@@ktc-junit:testStarted|id:test\\q|displayName:name|name:name")
    }

    @Test
    fun `message with a duplicate field is rejected`() {
        assertMalformed("@@ktc-junit:testStarted|id:test|id:duplicate|displayName:name|name:name")
    }

    @Test
    fun `message with an invalid numeric field is rejected`() {
        assertMalformed("@@ktc-junit:succeeded|id:test|duration:not-a-number")
    }

    @Test
    fun `message with a field not supported by its type is rejected`() {
        assertMalformed("@@ktc-junit:failed|id:test|text:value")
    }

    private fun assertMalformed(candidate: String) {
        assertThrows<IllegalArgumentException>(candidate) {
            JUnitEventProtocol.decode(candidate)
        }
    }
}
