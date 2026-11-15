/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceFileStorageTest {

    private val storage = ResourceFileStorage(
        resourceRootPath = "some-resource-dir",
        classLoader = javaClass.classLoader,
    )

    @Test
    fun `files are read from the resource root`() {
        assertEquals("{}", storage.read("sample.json")?.decodeToString())
    }

    @Test
    fun `the meta timestamp file is read from the resource root`() {
        assertEquals("1000", storage.read("sample.json.meta")?.decodeToString())
    }

    @Test
    fun `exists is false and read yields null for missing files`() {
        assertNull(storage.read("missing.json"))
        assertFalse(storage.exists("missing.json"))
    }

    @Test
    fun `the empty path exists, meaning the storage is available`() {
        assertTrue(storage.exists(""))
    }
}
