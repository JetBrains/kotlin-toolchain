/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.filechannels

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads the entire content of this [FileChannel] as a UTF-8 string.
 *
 * Malformed byte sequences are replaced by the replacement char `\uFFFD`.
 *
 * @throws java.nio.channels.ClosedChannelException If this channel is closed
 * @throws java.io.IOException If some other I/O error occurs
 */
fun FileChannel.readText() = readBytes().decodeToString()

/**
 * Reads the entire content of this [FileChannel] into a [ByteArray].
 *
 * @throws java.nio.channels.ClosedChannelException If this channel is closed
 * @throws java.io.IOException If some other I/O error occurs
 */
fun FileChannel.readBytes(): ByteArray {
    val size = size()
    if (size < 0) {
        // FileChannel.size() is documented to throw ClosedChannelException when the channel is closed. However, the
        // JDK's implementation (sun.nio.ch.FileChannelImpl) might not throw the exception and instead returns -1 in
        // some very specific conditions: the channel was closed concurrently after the initial open check, and it was
        // marked as "uninterruptible" by the caller.
        error(
            "Cannot read the contents of this file channel: its reported size is negative ($size). The channel " +
                "was probably closed concurrently and marked uninterruptible."
        )
    }
    if (size == 0L) {
        return ByteArray(0)
    }
    if (size > Int.MAX_VALUE) {
        error("this file is too big to fit in a ByteArray")
    }

    val buf = ByteArray(size.toInt())
    val bb = ByteBuffer.wrap(buf)

    position(0)
    while (bb.remaining() > 0) {
        val n = read(bb)
        if (n <= 0) {
            error("no bytes read from file")
        }
    }
    return buf
}

/**
 * Writes the given [text] encoded as UTF-8 to this [FileChannel] at the current position.
 *
 * @throws java.nio.channels.NonWritableChannelException If this channel was not opened for writing
 * @throws java.nio.channels.ClosedChannelException If this channel is closed
 * @throws java.nio.channels.AsynchronousCloseException If another thread closes this channel while the write operation
 * is in progress
 * @throws java.nio.channels.ClosedByInterruptException If another thread interrupts the current thread while the write
 * operation is in progress, thereby closing the channel and setting the current thread's interrupt status
 * @throws java.io.IOException If some other I/O error occurs
 */
fun FileChannel.writeText(text: String) {
    writeBytes(text.encodeToByteArray())
}

/**
 * Writes the given [bytes] to this [FileChannel] at the current position.
 *
 * @throws java.nio.channels.NonWritableChannelException If this channel was not opened for writing
 * @throws java.nio.channels.ClosedChannelException If this channel is closed
 * @throws java.nio.channels.AsynchronousCloseException If another thread closes this channel while the write operation
 * is in progress
 * @throws java.nio.channels.ClosedByInterruptException If another thread interrupts the current thread while the write
 * operation is in progress, thereby closing the channel and setting the current thread's interrupt status
 * @throws java.io.IOException If some other I/O error occurs
 */
fun FileChannel.writeBytes(bytes: ByteArray) {
    writeFully(ByteBuffer.wrap(bytes))
}

/**
 * Writes all bytes from the given [buffer] to this [FileChannel].
 *
 * The built-in [FileChannel.write] method doesn't guarantee that all bytes from the buffer are written to the file in a
 * single call, whereas this method does.
 *
 * @throws java.nio.channels.NonWritableChannelException If this channel was not opened for writing
 * @throws java.nio.channels.ClosedChannelException If this channel is closed
 * @throws java.nio.channels.AsynchronousCloseException If another thread closes this channel while the write operation
 * is in progress
 * @throws java.nio.channels.ClosedByInterruptException If another thread interrupts the current thread while the write
 * operation is in progress, thereby closing the channel and setting the current thread's interrupt status
 * @throws java.io.IOException If some other I/O error occurs
 */
fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.remaining() > 0) {
        val n = write(buffer)
        if (n <= 0) {
            error("no bytes written")
        }
    }
}

/**
 * Writes the content of the given [source] file to this [FileChannel].
 *
 * @throws UnsupportedOperationException If [source] is associated with a provider that does not support creating file
 * channels
 * @throws java.io.IOException If an I/O error occurs
 * @throws SecurityException If a security manager is installed, and it denies an unspecified permission required by the
 * implementation. In the case of the default provider, the [SecurityManager.checkRead] method is invoked to check read
 * access of [source].
 */
fun FileChannel.writeFrom(source: Path) {
    FileChannel.open(source, StandardOpenOption.READ).use { channel ->
        this.transferFrom(channel, 0, channel.size())
    }
}
