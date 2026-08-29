/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.hashing

import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Computes the hash of this [InputStream]'s data using the given [algorithm].
 *
 * The caller is responsible for closing the stream.
 *
 * @param algorithm a standard algorithm name as described in the
 *   [Java Security Standard Algorithm Names Specification](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html),
 *   in the _MessageDigest Algorithms_ section.
 */
fun InputStream.hash(algorithm: String): ByteArray = MessageDigest.getInstance(algorithm).digest(this)

/**
 * Computes the hash of these bytes using the given [algorithm].
 *
 * @param algorithm a standard algorithm name as described in the
 *   [Java Security Standard Algorithm Names Specification](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html),
 *   in the _MessageDigest Algorithms_ section.
 */
fun ByteArray.hash(algorithm: String): ByteArray = MessageDigest.getInstance(algorithm).digest(this)

/**
 * Computes the hash of this [String]'s UTF-8 representation using the given [algorithm].
 *
 * @param algorithm a standard algorithm name as described in the
 *   [Java Security Standard Algorithm Names Specification](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html),
 *   in the _MessageDigest Algorithms_ section.
 */
fun String.hash(algorithm: String): ByteArray = encodeToByteArray().hash(algorithm)

/**
 * Computes the hash of this file's contents using the given [algorithm].
 *
 * If this file is a directory, its entries are hashed instead, in alphabetical order.
 *
 * @param algorithm a standard algorithm name as described in the
 *   [Java Security Standard Algorithm Names Specification](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html),
 *   in the _MessageDigest Algorithms_ section.
 */
fun Path.hash(algorithm: String): ByteArray = MessageDigest.getInstance(algorithm).digest(this)

/**
 * Computes the hash of these files' contents, in the order they appear in the list, using the given [algorithm].
 *
 * If any of these files is a directory, its entries are hashed instead, in alphabetical order.
 *
 * @param algorithm a standard algorithm name as described in the
 *   [Java Security Standard Algorithm Names Specification](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html),
 *   in the _MessageDigest Algorithms_ section.
 */
fun List<Path>.hash(algorithm: String): ByteArray = MessageDigest.getInstance(algorithm).digest(this)
