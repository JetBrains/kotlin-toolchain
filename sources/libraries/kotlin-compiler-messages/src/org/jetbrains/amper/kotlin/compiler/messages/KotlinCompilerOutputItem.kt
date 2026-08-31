/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.compiler.messages

/**
 * A meaningful piece of the output of a Kotlin CLI compiler, as recognized by [KotlinCompilerOutputParser].
 */
sealed interface KotlinCompilerOutputItem

/**
 * A message printed by a Kotlin CLI compiler, such as a diagnostic about the compiled code, or a log about the
 * compilation itself (depending on the [severity]).
 */
data class KotlinCompilerMessage(
    /**
     * The severity of this message.
     */
    val severity: Severity,
    /**
     * The text of the message, which may span multiple lines. It never contains the source code snippet printed by the
     * compiler under located messages (see [location]).
     */
    val text: String,
    /**
     * The place in the source code that this message is about, or null if the message is not about a specific place
     * (a global compiler message).
     */
    val location: Location?,
) : KotlinCompilerOutputItem {

    /**
     * The severity of a [KotlinCompilerMessage], mirroring
     * `org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity`.
     *
     * Note that the CLI compilers print both regular and strong warnings as plain `warning`, so both map to [Warning].
     */
    enum class Severity(internal val cliName: String) {
        Exception("exception"),
        Error("error"),
        Warning("warning"),
        Info("info"),
        Logging("logging"),
        Output("output"),
    }

    /**
     * The location of a [KotlinCompilerMessage] in the source code.
     */
    data class Location(
        /**
         * The path to the file, as printed by the compiler. It can be relative to the working directory of the compiler
         * process.
         */
        val path: String,
        /**
         * The 1-based line number.
         */
        val line: Int,
        /**
         * The 1-based column number where the highlighted range starts, or null if the compiler didn't print it.
         */
        val columnStart: Int?,
        /**
         * The 1-based column number just after the end of the highlighted range (exclusive), or null if the compiler
         * didn't print the source code snippet that materializes this range.
         */
        val columnEnd: Int?,
    )
}

/**
 * A line of the compiler output that is not part of any compiler message. This is usually the output of other tools
 * spawned by the compiler (linkers, C compilers), or of the JVM running the compiler.
 */
data class UnrecognizedOutputLine(val text: String) : KotlinCompilerOutputItem
