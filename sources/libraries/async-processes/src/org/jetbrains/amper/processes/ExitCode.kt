/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

@JvmInline
value class ExitCode(val value: Int) {

    /**
     * This exit code value, formatted with optional additional details about its meaning for abnormal termination
     * (a crash, or termination by a signal via the `kill` command).
     *
     * - Small positive exit codes are usually normal and kept as-is.
     * - Negative exit codes are Windows-specific NTSTATUS values, and are formatted as hexadecimal (with their message
     *   in parentheses).
     * - Unix-like systems report processes terminated by a signal as 128 + the signal number.
     *
     * Examples:
     * ```
     * 0
     * 1
     * 2
     * 0xC0000005 (STATUS_ACCESS_VIOLATION)
     * 0xC0000374 (STATUS_HEAP_CORRUPTION)
     * 0xC0000409 (STATUS_STACK_BUFFER_OVERRUN)
     * 0xC000013A (STATUS_CONTROL_C_EXIT)
     * 130 (terminated by SIGINT)
     * 137 (terminated by SIGKILL)
     * ```
     */
    val formattedValue: String
        get() = when {
            // Windows reports crashes as NTSTATUS values, which are negative when read as signed ints, and are unreadable in
            // decimal form. Unix-like systems are constrained to 0-255, so they won't hit this branch.
            value < 0 -> "0x%08X%s".format(value, ntStatusMeaning(value)?.let { " ($it)" }.orEmpty())
            // Unix-like systems report processes killed by a signal as 128 + the signal number.
            value > 128 -> "$value (terminated by ${signalName(value - 128)})"
            else -> "$value"
        }

    /**
     * A user-readable representation of this exit code value, formatted with optional additional details about its
     * meaning for abnormal termination (a crash, or a kill by a signal).
     *
     * @see formattedValue
     */
    override fun toString(): String = formattedValue
}

// https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-erref/596a1078-e883-4972-9bbc-49e60bebca55
private fun ntStatusMeaning(exitCode: Int): String? = when (exitCode) {
    // Memory and execution faults
    0xC0000005.toInt() -> "STATUS_ACCESS_VIOLATION"
    0xC0000006.toInt() -> "STATUS_IN_PAGE_ERROR"
    0xC000001D.toInt() -> "STATUS_ILLEGAL_INSTRUCTION"
    0xC0000094.toInt() -> "STATUS_INTEGER_DIVIDE_BY_ZERO"
    0xC0000095.toInt() -> "STATUS_INTEGER_OVERFLOW"
    0xC0000096.toInt() -> "STATUS_PRIVILEGED_INSTRUCTION"
    0xC00000FD.toInt() -> "STATUS_STACK_OVERFLOW"

    // Debugging and explicit termination
    0x80000003.toInt() -> "STATUS_BREAKPOINT"
    0xC0000025.toInt() -> "STATUS_NONCONTINUABLE_EXCEPTION"
    0xC000013A.toInt() -> "STATUS_CONTROL_C_EXIT"
    0x40000015.toInt() -> "STATUS_FATAL_APP_EXIT"
    0xC0000420.toInt() -> "STATUS_ASSERTION_FAILURE"

    // Loader failures
    0xC000007B.toInt() -> "STATUS_INVALID_IMAGE_FORMAT"
    0xC0000135.toInt() -> "STATUS_DLL_NOT_FOUND"
    0xC0000139.toInt() -> "STATUS_ENTRYPOINT_NOT_FOUND"
    0xC0000142.toInt() -> "STATUS_DLL_INIT_FAILED"

    // Corruption and fail-fast termination
    0xC0000374.toInt() -> "STATUS_HEAP_CORRUPTION"
    0xC0000409.toInt() -> "STATUS_STACK_BUFFER_OVERRUN"
    0xC0000602.toInt() -> "STATUS_FAIL_FAST_EXCEPTION"

    else -> null
}

private fun signalName(signalValue: Int): String = when (signalValue) {
    2 -> "SIGINT"
    6 -> "SIGABRT"
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    15 -> "SIGTERM"
    else -> "signal $signalValue"
}
