/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.listeners

import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates a [PrintStream] that reports each flushed output to [onTextPrinted]
 * attributed to the value stored in [threadLocalKey].
 *
 * If you write bytes directly via [PrintStream.writeBytes] or [PrintStream.write], they are interpreted as UTF-8.
 */
// We could've made ThreadAwareOutputStream extend PrintStream instead, but PrintStream.println has optimization
// to do a single flush only when the instance.getClass() == PrintStream instead of instanceof check (backwards
// compatibility reasons). Thus, to make the same optimization work, we'll have to override each println method
// which is a burden.
// Instead, we wrap the ThreadAwareOutputStream into a PrintStream instead accepting the cost of encoding-decoding
// String to byte array and back.
fun <K> threadAwarePrintStream(
    threadLocalKey: ThreadLocal<K>,
    onTextPrinted: (key: K, line: String) -> Unit,
): PrintStream = PrintStream(
    ThreadAwareOutputStream(
        threadLocalKey = threadLocalKey,
        onTextPrinted = onTextPrinted,
    ),
    true,
    Charsets.UTF_8,
)

/**
 * An [OutputStream] that remembers the output independently for each value
 * of the given [threadLocalKey], and reports flushed text to [onTextPrinted] decoded as [charset], attributed to each key.
 */
private class ThreadAwareOutputStream<K>(
    private val threadLocalKey: ThreadLocal<K>,
    private val onTextPrinted: (key: K, line: String) -> Unit,
) : OutputStream() {
    // Used when thread local key is `null`
    private val unattributedThreadBuffer = ThreadLocal.withInitial(::StringBuffer)
    private val threadBuffers =
        ConcurrentHashMap<K & Any, StringBuffer>() // not StringBuilder because we want thread safety

    private fun getThreadBuffer(): StringBuffer {
        val key = threadLocalKey.get() ?: return unattributedThreadBuffer.get()
        return threadBuffers.computeIfAbsent(key) { StringBuffer() }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        // NB: Charset has to match the one created in `threadAwarePrintStream`
        getThreadBuffer().append(String(buf, off, len, Charsets.UTF_8))
    }

    override fun write(b: Int) {
        getThreadBuffer().append(b.toChar().toString())
    }

    override fun flush() {
        val buffer = getThreadBuffer()
        val text = buffer.toString()
        if (text.isEmpty()) return
        onTextPrinted(threadLocalKey.get(), text)
        buffer.setLength(0)
    }
}
