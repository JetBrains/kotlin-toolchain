/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlin.metadata.format.projectStructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test

internal class KotlinProjectStructureJsonTest {

    fun getTestDataPath(name: String): Path =
        Path.of(
            this::class.java.getClassLoader()
                .getResource("metadata/json/projectStructure/$name/kotlin-project-structure-metadata.json").toURI()
        )

    fun String.parse(): KotlinProjectStructureMetadata = parseKmpLibraryMetadata()

    @Test
    fun `kotlinx-coroutines-core-metadata-1_7_3`(testInfo: TestInfo) = doTest(testInfo)

    @Test
    fun `kotlinx-datetime-0_4_0-all`(testInfo: TestInfo) = doTest(testInfo)

    private fun doTest(testInfo: TestInfo, sanitizer: (String) -> String = { it }) {
        val expectedText = getTestDataText(testInfo.nameToDependency())
        val model = expectedText.parse()
        assertEquals(sanitizer(sanitize(expectedText)), sanitizer(model.serialize()))
    }

    private fun getTestDataText(name: String) = getTestDataPath(name).readText()

    private fun sanitize(text: String) = text.replace("\\s+".toRegex(), "")

    private fun TestInfo.nameToDependency(): String = testMethod.get().name.replace('_', '.').replace(' ', ':')
}