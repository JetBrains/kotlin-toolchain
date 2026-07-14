/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.gradle.module.metadata.format

import org.jetbrains.kotlin.metadata.format.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class ModuleJsonTest {

    fun getTestDataPath(name: String): Path =
        Path.of(this::class.java.getClassLoader().getResource("module/$name.module").toURI())

    fun String.parse(): Module = json.decodeFromString(this)

    @Test
    fun `kotlin-stdlib-1_9_20`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlin-test-1_9_20`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlin-test-junit-1_9_20`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlinx-coroutines-core-1_6_4`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlinx-coroutines-core-jvm-1_6_4`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlinx-coroutines-android-1_5_0`(testInfo: TestInfo) = doTest(testInfo) { it.replace("6,", "\"6\",") }

    @Test
    fun `guava-33_0_0-android`(testInfo: TestInfo) = doTest(testInfo) {
        it.replace(
            "ApacheMaven3.9.5(57804ffe001d7215b5e7bcb531cf83df38f93546)",
            "Apache Maven 3.9.5 (57804ffe001d7215b5e7bcb531cf83df38f93546)"
        )
    }

    @Test
    fun `atomicfu-0_23_2`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `absence of size is null`() {
        // some metadata may not specify file size
        // we need to correctly parse that, absence of size is null, not 0

        // guava does not specify size and checksums
        val text = getTestDataText("guava-33.0.0-android")
        val module = text.parseMetadata()
        assertTrue(module.variants.flatMap { it.files }.all {
            it.size == null && it.md5 == null && it.sha1 == null && it.sha256 == null && it.sha512 == null
        })
    }

    private fun doTest(testInfo: TestInfo, sanitizer: (String) -> String = { it }) {
        val expectedText = getTestDataText(testInfo.nameToDependency())
        val model = expectedText.parse()
        assertEquals(sanitizer(sanitize(expectedText)), sanitizer(model.serialize()))
    }

    private fun getTestDataText(name: String) = getTestDataPath(name).readText()

    private fun sanitize(text: String) = text.replace("\\s+".toRegex(), "")

    private fun TestInfo.nameToDependency(): String = testMethod.get().name.replace('_', '.').replace(' ', ':')
}