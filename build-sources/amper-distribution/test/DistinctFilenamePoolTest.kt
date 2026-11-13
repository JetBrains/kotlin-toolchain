/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistinctFilenamePoolTest {

    @Test
    fun `first registration returns the original filename`() {
        val pool = DistinctFilenamePool()

        assertEquals("kotlin-stdlib.jar", pool.registerAndGetName(Path("some-dir/kotlin-stdlib.jar")))
    }

    @Test
    fun `same filename in a different directory gets a suffixed unique name`() {
        val pool = DistinctFilenamePool()
        pool.registerAndGetName(Path("dir1", "annotations.jar"))
        val secondName = pool.registerAndGetName(Path("dir2", "annotations.jar"))

        assertTrue(secondName.startsWith("annotations"), "original filename should be preserved as prefix")
        assertTrue(secondName.endsWith(".jar"), "extension should be preservec")
    }

    @Test
    fun `registering the same path twice returns the same name`() {
        val pool = DistinctFilenamePool()
        val path = Path("dir", "library.jar")

        val firstName = pool.registerAndGetName(path)
        val secondName = pool.registerAndGetName(path)

        assertEquals(firstName, secondName)
        assertEquals(["library.jar"], pool.list())
    }

    @Test
    fun `list respects registration order`() {
        val pool = DistinctFilenamePool()

        [Path("dir1/a.jar"), Path("dir2/b.jar"), Path("dir3/c.jar")].forEach {
            pool.registerAndGetName(it)
        }

        assertEquals(["a.jar", "b.jar", "c.jar"], pool.list())
    }

    @Test
    fun `several files with the same filename all get distinct names`() {
        val pool = DistinctFilenamePool()
        val paths = [Path("dir1/dup.jar"), Path("dir2/dup.jar"), Path("dir3/dup.jar")]

        val names = paths.map { pool.registerAndGetName(it) }

        assertEquals(names.size, names.toSet().size)
        assertEquals("dup.jar", names.first())
    }
}
