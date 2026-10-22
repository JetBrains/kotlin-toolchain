/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XcarchiveDistributionsTest {
    @Test
    fun `finds the successful upload added during this run`() {
        val distributions = [
            distribution(identifier = "old-upload", state = "success"),
            distribution(identifier = "new-upload", state = "success"),
        ]

        assertEquals("new-upload", newUploadId(setOf("old-upload"), distributions))
    }

    @Test
    fun `fails when Xcode did not add an upload`() {
        val error = assertFailsWith<IllegalStateException> {
            newUploadId(setOf("old-upload"), emptyList<Any>())
        }

        assertContains(error.message.orEmpty(), "exactly one")
    }

    @Test
    fun `fails when the new upload did not succeed`() {
        val distributions = [distribution(identifier = "new-upload", state = "failed")]

        val error = assertFailsWith<IllegalStateException> {
            newUploadId(emptySet(), distributions)
        }

        assertContains(error.message.orEmpty(), "state 'failed'")
    }

    @Test
    fun `fails when Xcode added multiple uploads`() {
        val distributions = [
            distribution(identifier = "first-upload", state = "success"),
            distribution(identifier = "second-upload", state = "success"),
        ]

        val error = assertFailsWith<IllegalStateException> {
            newUploadId(emptySet(), distributions)
        }

        assertContains(error.message.orEmpty(), "exactly one")
    }

    private fun distribution(identifier: String, state: String): Map<String, Any> = mapOf(
        "identifier" to identifier,
        "destination" to "upload",
        "uploadEvent" to mapOf("state" to state),
    )
}
