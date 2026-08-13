/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvironmentFileTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun parsesRepeatedEnvironmentFileArguments() {
        val result = parseEnvironmentFileArguments(
            arrayOf("--env-file", ".env", "--env-file=.env.dev", "run", "--", "--env-file", "app.env")
        )

        assertEquals(listOf(Path.of(".env"), Path.of(".env.dev")), result.files)
        assertEquals(listOf("run", "--", "--env-file", "app.env"), result.forwardedArguments)
    }

    @Test
    fun laterFilesOverrideEarlierFilesButNotInheritedEnvironment() {
        val sharedFile = tempDir.resolve(".env").also { file ->
            file.writeText(
                """
                SHARED=shared
                LAYER=shared
                INHERITED=shared
                """.trimIndent()
            )
        }
        val developmentFile = tempDir.resolve(".env.dev").also { file ->
            file.writeText(
                """
                DEVELOPMENT=development
                LAYER=development
                INHERITED=development
                """.trimIndent()
            )
        }

        val result = loadEnvironmentFiles(
            files = listOf(sharedFile, developmentFile),
            inheritedEnvironment = mapOf("INHERITED" to "process"),
        )

        assertEquals(
            mapOf(
                "SHARED" to "shared",
                "LAYER" to "development",
                "DEVELOPMENT" to "development",
            ),
            result,
        )
    }

    @Test
    fun parsesCommentsBlankLinesAndQuotedValues() {
        val file = tempDir.resolve(".env.prod").also { path ->
            path.writeText(
                """
                # Production settings

                PLAIN=value
                SINGLE_QUOTED='value with spaces'
                DOUBLE_QUOTED="value=with=separators"
                EMPTY=
                """.trimIndent()
            )
        }

        val result = loadEnvironmentFiles(listOf(file), inheritedEnvironment = emptyMap())

        assertEquals(
            mapOf(
                "PLAIN" to "value",
                "SINGLE_QUOTED" to "value with spaces",
                "DOUBLE_QUOTED" to "value=with=separators",
                "EMPTY" to "",
            ),
            result,
        )
    }

    @Test
    fun rejectsInvalidDeclarations() {
        val file = tempDir.resolve(".env").also { path ->
            path.writeText("INVALID DECLARATION")
        }

        val error = assertFailsWith<UserReadableError> {
            loadEnvironmentFiles(listOf(file), inheritedEnvironment = emptyMap())
        }

        assertEquals("Invalid environment variable declaration in $file at line 1", error.message)
    }
}
