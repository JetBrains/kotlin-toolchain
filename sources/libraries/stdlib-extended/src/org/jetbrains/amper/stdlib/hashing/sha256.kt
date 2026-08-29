/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.hashing

import java.nio.file.Path

/**
 * Returns the SHA-256 hash of these bytes.
 */
fun ByteArray.sha256(): ByteArray = hash("SHA-256")

/**
 * Returns the SHA-256 hash of the UTF-8 representation of this string.
 */
fun String.sha256(): ByteArray = encodeToByteArray().sha256()

/**
 * Returns the SHA-256 hash of this file.
 *
 * If this file is a directory, all its entries are hashes in alphabetical order.
 */
fun Path.sha256(): ByteArray = hash("SHA-256")

/**
 * Returns the SHA-256 hash of these bytes, as a hexadecimal string.
 */
fun ByteArray.sha256String(): String = sha256().toHexString()

/**
 * Returns the SHA-256 hash of the UTF-8 representation of this string, as a hexadecimal string.
 */
fun String.sha256String(): String = sha256().toHexString()

/**
 * Returns the SHA-256 hash of this file, as a hexadecimal string.
 *
 * If this file is a directory, all its entries are hashes in alphabetical order.
 */
fun Path.sha256String(): String = sha256().toHexString()
