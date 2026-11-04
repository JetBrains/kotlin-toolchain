/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.apple.ffm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Downcalls into the CoreFoundation framework.
 */
@Suppress("FunctionName")
object CoreFoundation {

    const val KCF_STRING_ENCODING_UTF8 = 0x08000100

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        Arena.global(),
    )

    private val CFStringCreateWithCString: MethodHandle = lookup.downcall(
        "CFStringCreateWithCString", FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT
        ),
    )

    private val CFStringGetCString: MethodHandle = lookup.downcall(
        "CFStringGetCString", FunctionDescriptor.of(
            ValueLayout.JAVA_BYTE,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT
        ),
    )

    private val CFRelease: MethodHandle = lookup.downcall(
        "CFRelease", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    /**
     * Creates an immutable string from a C string.
     *
     * A C string is a string of 8-bit characters terminated with an 8-bit NULL. Unichar and Unichar32 are not considered C strings.
     *
     * @param alloc The allocator to use to allocate memory for the new string. Pass NULL or kCFAllocatorDefault to use
     *   the current default allocator.
     * @param cStr The NULL-terminated C string to be used to create the CFString object. The string must use an 8-bit
     *   encoding.
     * @param encoding The encoding of the characters in the C string. The encoding must specify an 8-bit encoding.
     *
     * @return An immutable string containing cStr (after stripping off the NULL terminating character), or NULL if
     * there was a problem creating the object. Ownership follows the
     * [The Create Rule](https://developer.apple.com/library/archive/documentation/CoreFoundation/Conceptual/CFMemoryMgmt/Concepts/Ownership.html#//apple_ref/doc/uid/20001148-103029).
     */
    fun CFStringCreateWithCString(
        alloc: MemorySegment = MemorySegment.NULL,
        cStr: MemorySegment,
        encoding: Int = KCF_STRING_ENCODING_UTF8,
    ): MemorySegment = CFStringCreateWithCString.invokeExact(alloc, cStr, encoding) as MemorySegment

    /**
     * Copies the character contents of a string to a local C string buffer after converting the characters to a given
     * encoding.
     *
     * This function is useful when you need your own copy of a string's character data as a C string. You also
     * typically call it as a "backup" when a prior call to the `CFStringGetCStringPtr` function fails.
     *
     * @param theString The string whose contents you wish to access.
     * @param buffer The C string buffer into which to copy the string. On return, the buffer contains the converted
     *   characters. If there is an error in conversion, the buffer contains only partial results.
     *   The buffer must be large enough to contain the converted characters and a `NUL` terminator. For example, if
     *   the string is `Toby`, the buffer must be at least 5 bytes long.
     * @param bufferSize The length of [buffer] in bytes. Defaults to the whole size of [buffer].
     * @param encoding The string encoding to which the character contents of [theString] should be converted. The
     *   encoding must specify an 8-bit encoding.
     *
     * @return `true` upon success, or `false` if the conversion fails or the provided buffer is too small.
     */
    fun CFStringGetCString(
        theString: MemorySegment,
        buffer: MemorySegment,
        bufferSize: Long = buffer.byteSize(),
        encoding: Int = KCF_STRING_ENCODING_UTF8,
    ): Boolean = CFStringGetCString.invokeExact(theString, buffer, bufferSize, encoding) as Byte != 0.toByte()

    /**
     * Releases a Core Foundation object.
     *
     * If the retain count of [cf] becomes zero, the memory allocated to the object is deallocated and the object is
     * destroyed. If you create, copy, or explicitly retain a Core Foundation object, you are responsible for releasing
     * it when you no longer need it.
     *
     * @param cf A CFType object to release. This value must not be `NULL`, as this causes a runtime error that crashes
     *   the process.
     */
    fun CFRelease(cf: MemorySegment) {
        CFRelease.invokeExact(cf)
    }
}

/**
 * Allocates a CFString from the given [string] and passes the CFString to the given [block].
 * The CFString is released after the [block] returns, normally or exceptionally.
 */
context(arena: Arena)
inline fun <T> CoreFoundation.withCFString(string: String, block: (cfString: MemorySegment) -> T): T {
    val key = CFStringCreateWithCString(cStr = arena.allocateFrom(string))
    try {
        return block(key)
    } finally {
        CFRelease(key)
    }
}
