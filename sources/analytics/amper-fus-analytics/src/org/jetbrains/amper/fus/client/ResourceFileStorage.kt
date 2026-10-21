/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.FileHandle
import com.jetbrains.fus.reporting.FileStorage
import com.jetbrains.fus.reporting.FileStorageMode
import java.net.URL

/**
 * A [FileStorage] implementation that loads files from resources.
 */
internal class ResourceFileStorage(
    /**
     * The root path of the resources to load. Paths are resolved relative to that root.
     */
    private val resourceRootPath: String,
    /**
     * The [ClassLoader] to load resources from.
     */
    private val classLoader: ClassLoader,
) : UrlBasedFileStorage {
    override fun getUrl(path: String): URL? = classLoader.getResource("$resourceRootPath/${path.removePrefix("/")}")
}

private interface UrlBasedFileStorage : FileStorage {

    fun getUrl(path: String): URL?

    override fun exists(path: String): Boolean = path.isEmpty() || getUrl(path) != null

    override fun read(path: String): ByteArray? = getUrl(path)?.openStream()?.use { it.readAllBytes() }

    override fun list(path: String): List<String> = emptyList()

    override fun delete(path: String) = Unit

    override fun write(path: String, content: ByteArray) = Unit

    override fun openFileHandle(path: String, mode: FileStorageMode): FileHandle = object : FileHandle {
        override val name: String = path
        override fun exists(): Boolean = false
        override fun length(): Long = 0
        override fun readAll(): ByteArray = ByteArray(0)
        override fun read(index: Int, dstBuffer: ByteArray): ByteArray = ByteArray(0)
        override fun read(index: Int): Byte = 0
        override fun write(bytes: ByteArray) = Unit
        override fun close() = Unit
    }
}
