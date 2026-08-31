/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.compiler.messages

import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage.Location
import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * All the compiler outputs used in these tests are real outputs of the Kotlin 2.4.0 CLI compilers.
 */
class KotlinCompilerOutputParserTest {

    @Test
    fun `parses global messages without location`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Warning,
                    text = "flag is not supported by this version of the compiler: -Xfoo-bar",
                    location = null,
                ),
                KotlinCompilerMessage(
                    severity = Severity.Logging,
                    text = "using Kotlin home directory dist/kotlinc",
                    location = null,
                ),
            ],
            parseOutput(
                """
                warning: flag is not supported by this version of the compiler: -Xfoo-bar
                logging: using Kotlin home directory dist/kotlinc
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parses all severities`() {
        assertEquals(
            Severity.entries.map {
                KotlinCompilerMessage(severity = it, text = "some message", location = null)
            },
            parseOutput(Severity.entries.joinToString("\n") { "${it.cliName}: some message" }),
        )
    }

    @Test
    fun `parses located messages and drops the source code snippet`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Error,
                    text = "unresolved reference 'undefinedThing'.",
                    location = Location(
                        path = "src/Foo.kt",
                        line = 5,
                        columnStart = 13,
                        columnEnd = 27, // derived from the 14 carets in the snippet
                    ),
                ),
                KotlinCompilerMessage(
                    severity = Severity.Warning,
                    text = "'fun old(): Unit' is deprecated. old.",
                    location = Location(
                        path = "/home/me/project/src/Warn.kt",
                        line = 8,
                        columnStart = 5,
                        columnEnd = 8,
                    ),
                ),
            ],
            parseOutput(
                """
                src/Foo.kt:5:13: error: unresolved reference 'undefinedThing'.
                    println(undefinedThing)
                            ^^^^^^^^^^^^^^
                /home/me/project/src/Warn.kt:8:5: warning: 'fun old(): Unit' is deprecated. old.
                    old()
                    ^^^
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parses located messages with Windows paths`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Error,
                    text = "initializer type mismatch: expected 'String', actual 'Int'.",
                    location = Location(
                        path = """C:\Users\me\project\src\Foo.kt""",
                        line = 6,
                        columnStart = 19,
                        columnEnd = 20,
                    ),
                ),
            ],
            parseOutput(
                """
                C:\Users\me\project\src\Foo.kt:6:19: error: initializer type mismatch: expected 'String', actual 'Int'.
                    val x: String = 42
                                  ^
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parses located messages without column`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Error,
                    text = "some message about the whole line",
                    location = Location(path = "src/Foo.kt", line = 5, columnStart = null, columnEnd = null),
                ),
            ],
            parseOutput("src/Foo.kt:5: error: some message about the whole line"),
        )
    }

    @Test
    fun `parses multi-line messages, including blank lines`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Error,
                    text = """
                        none of the following candidates is applicable:

                        fun f(x: Int): Unit:
                          Null cannot be a value of a non-null type 'Int'.

                        fun f(x: String): Unit:
                          Null cannot be a value of a non-null type 'String'.
                    """.trimIndent(),
                    location = Location(path = "src/Ambig.kt", line = 7, columnStart = 5, columnEnd = 6),
                ),
            ],
            parseOutput(
                """
                src/Ambig.kt:7:5: error: none of the following candidates is applicable:

                fun f(x: Int): Unit:
                  Null cannot be a value of a non-null type 'Int'.

                fun f(x: String): Unit:
                  Null cannot be a value of a non-null type 'String'.
                    f(null)
                    ^
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parses exceptions with their stack trace`() {
        assertEquals(
            [
                KotlinCompilerMessage(
                    severity = Severity.Exception,
                    text = """
                        java.lang.NoClassDefFoundError: kotlinx/coroutines/CoroutineScope
                        	at org.jetbrains.kotlin.cli.jvm.K2JVMCompiler.main(K2JVMCompiler.kt)
                        Caused by: java.lang.ClassNotFoundException: kotlinx.coroutines.CoroutineScope
                        	... 34 more
                    """.trimIndent(),
                    location = null,
                ),
            ],
            parseOutput(
                """
                exception: java.lang.NoClassDefFoundError: kotlinx/coroutines/CoroutineScope
                	at org.jetbrains.kotlin.cli.jvm.K2JVMCompiler.main(K2JVMCompiler.kt)
                Caused by: java.lang.ClassNotFoundException: kotlinx.coroutines.CoroutineScope
                	... 34 more
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `reports lines that are not part of a compiler message`() {
        assertEquals(
            [
                UnrecognizedOutputLine("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called"),
                UnrecognizedOutputLine("ld: warning: ignoring duplicate libraries: '-lc'"),
                UnrecognizedOutputLine(""),
                KotlinCompilerMessage(Severity.Error, "some error", location = null),
            ],
            parseOutput(
                """
                WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
                ld: warning: ignoring duplicate libraries: '-lc'

                error: some error
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `only completes a message when the next one starts`() {
        var lastMessage: KotlinCompilerOutputItem? = null
        val parser = KotlinCompilerOutputParser { lastMessage = it }

        parser.consumeLine("warning: some warning")
        assertNull(lastMessage, "the message could still continue")
        parser.consumeLine("and this is its second line")
        assertNull(lastMessage, "the message could still continue")

        val firstMessage = KotlinCompilerMessage(
            severity = Severity.Warning,
            text = "some warning\nand this is its second line",
            location = null,
        )
        parser.consumeLine("error: some error") // start of another message
        assertEquals(firstMessage, lastMessage)
        parser.flush()
        assertEquals(
            KotlinCompilerMessage(Severity.Error, "some error", location = null),
            lastMessage,
        )
    }

    private fun parseOutput(output: String): List<KotlinCompilerOutputItem> = buildList {
        val parser = KotlinCompilerOutputParser { add(it) }
        output.lines().forEach { line ->
            parser.consumeLine(line)
        }
        parser.flush()
    }
}
