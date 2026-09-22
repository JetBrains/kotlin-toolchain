/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package demo.app

import com.example.gen.Res
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose resources declared by a test fragment are packaged on the test classpath, which is where the resources
 * runtime reads them from on the JVM, just like the ones of the main fragments.
 */
class TestResourcesTest {
    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `resources of the test fragment are readable`() = runBlocking {
        assertEquals("Test text", Res.readBytes("files/test-text.txt").decodeToString())
    }
}
