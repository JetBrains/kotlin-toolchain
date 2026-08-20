/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.stdlib.hashing.hash
import org.jetbrains.amper.test.runTestWithMdc
import org.jetbrains.gradle.module.metadata.format.Variant
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.div
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleLocalRepositoryTest: BaseDRTest() {
    @TempDir
    lateinit var gradleLocalPath: Path

    private val gradleLocalRepository: GradleLocalRepository
        get() = GradleLocalRepository(gradleLocalPath)

    @Test
    fun `get name without extension`() {
        assertEquals("kotlin-test-1.9.10", kotlinTest().coordinates.getNameWithoutExtension())
    }

    @Test
    fun `guess path`() = runTestWithMdc {
        val node = kotlinTest()
        var path = gradleLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar")
        assertNull(path)

        val sha1 = randomString().hash("sha1").toHexString()

        val baseDir = gradleLocalPath / "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1"
        baseDir.createDirectories()
        (baseDir / "kotlin-test-1.9.10.jar").createFile()

        path = gradleLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar")
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1/kotlin-test-1.9.10.jar",
            path?.relativeTo(gradleLocalPath)?.toString()?.replace('\\', '/')
        )
    }

    @Test
    fun `guess path with variant`() = runTestWithMdc {
        val sha1 = randomString().hash("sha1").toHexString()
        val node = kotlinTest().also {
            it.variants = listOf(
                Variant(
                    "",
                    files = listOf(
                        org.jetbrains.gradle.module.metadata.format.File(
                            name = "kotlin-test-1.9.10.jar",
                            "kotlin-test-1.9.10.jar",
                            0,
                            "",
                            "",
                            sha1 = sha1,
                            "",
                        )
                    ),
                )
            )
        }
        val path = gradleLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar")
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1/kotlin-test-1.9.10.jar",
            path?.relativeTo(gradleLocalPath)?.toString()?.replace('\\', '/')
        )
    }

    /**
     * Some artifacts are declared in Gradle metadata with a `name` that differs from the name of the file
     * in a repository (`url`), e.g., a KMP metadata jar or a KMP resources archive.
     * Gradle stores such a file under the name from metadata, that is what should be guessed.
     */
    @Test
    fun `guess path with variant declaring a name that differs from the file name in a repository`() = runTestWithMdc {
        val sha1 = computeHash("sha1", randomString().toByteArray()).hash
        val fileNameInRepository = "kotlin-test-1.9.10-kotlin_resources.kotlin_resources.zip"
        val nameInMetadata = "library-1.9.10.kotlin_resources.zip"
        val node = kotlinTest().also {
            it.variants = listOf(
                Variant(
                    "",
                    files = listOf(
                        org.jetbrains.gradle.module.metadata.format.File(
                            name = nameInMetadata,
                            fileNameInRepository,
                            0,
                            "",
                            "",
                            sha1 = sha1,
                            "",
                        )
                    ),
                )
            )
        }

        val baseDir = gradleLocalPath / "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1"
        baseDir.createDirectories()
        (baseDir / nameInMetadata).createFile()

        val path = gradleLocalRepository.guessPath(node, fileNameInRepository)
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1/$nameInMetadata",
            path?.relativeTo(gradleLocalPath)?.toString()?.replace('\\', '/')
        )
    }

    /**
     * Files are stored in this layout under the name declared in Gradle metadata (as Gradle itself does),
     * otherwise they would not be found by [GradleLocalRepository.guessPath] afterward.
     */
    @Test
    fun `get path of a file with a name that differs from the file name in a repository`() = runTestWithMdc {
        val sha1 = computeHash("sha1", randomString().toByteArray()).hash
        val fileNameInRepository = "kotlin-test-1.9.10-kotlin_resources.kotlin_resources.zip"
        val nameInMetadata = "library-1.9.10.kotlin_resources.zip"
        val node = kotlinTest().also {
            it.variants = listOf(
                Variant(
                    "",
                    files = listOf(
                        org.jetbrains.gradle.module.metadata.format.File(
                            name = nameInMetadata,
                            fileNameInRepository,
                            0,
                            "",
                            "",
                            sha1 = sha1,
                            "",
                        )
                    ),
                )
            )
        }

        val path = gradleLocalRepository.getPath(node, fileNameInRepository, sha1)
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1/$nameInMetadata",
            path.relativeTo(gradleLocalPath).toString().replace('\\', '/')
        )
    }

    @Test
    fun `get path`() {
        val sha1 = randomString().hash("sha1").toHexString()
        val path = gradleLocalRepository.getPath(kotlinTest(), "${kotlinTest().coordinates.getNameWithoutExtension()}.jar", sha1)
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$sha1/kotlin-test-1.9.10.jar",
            path.relativeTo(gradleLocalPath).toString().replace('\\', '/')
        )
    }

    /**
     * Gradle names a directory in this layout after a hash the way it formats hashes, i.e., with stripped
     * leading zeros. A hash published in Gradle metadata is formatted the same way, thus it is used as is.
     */
    @Test
    fun `guess path with variant declaring a hash with stripped leading zeros`() = runTestWithMdc {
        // a sha1 is 40 characters long, Gradle strips leading zeros both in the published hash and in the path
        val strippedSha1 = "1".repeat(39)
        val node = kotlinTest().also {
            it.variants = listOf(
                Variant(
                    "",
                    files = listOf(
                        org.jetbrains.gradle.module.metadata.format.File(
                            name = "kotlin-test-1.9.10.jar",
                            url = "kotlin-test-1.9.10.jar",
                            sha1 = strippedSha1,
                        )
                    ),
                )
            )
        }

        val path = gradleLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar")
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$strippedSha1/kotlin-test-1.9.10.jar",
            path?.relativeTo(gradleLocalPath)?.toString()?.replace('\\', '/')
        )
    }

    /**
     * A record without a hash tells nothing about the directory a file is stored in. In the Gradle layout,
     * such a path should not be guessed at all (it used to be guessed as a 'null' directory).
     */
    @Test
    fun `guess path with variant declaring no hash`() = runTestWithMdc {
        val node = kotlinTest().also {
            it.variants = listOf(
                Variant(
                    "",
                    files = listOf(
                        org.jetbrains.gradle.module.metadata.format.File(
                            name = "kotlin-test-1.9.10.jar",
                            url = "kotlin-test-1.9.10.jar",
                        )
                    ),
                )
            )
        }

        assertNull(gradleLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar"))
    }

    /**
     * A file is stored in a directory named exactly after the given hash (as Gradle itself does),
     * otherwise it would not be found by [GradleLocalRepository.guessPath] afterward.
     */
    @Test
    fun `get path of a file with a hash with stripped leading zeros`() {
        val strippedSha1 = "1".repeat(39)
        val path = gradleLocalRepository.getPath(kotlinTest(), "${kotlinTest().coordinates.getNameWithoutExtension()}.jar", strippedSha1)
        assertEquals(
            "org.jetbrains.kotlin/kotlin-test/1.9.10/$strippedSha1/kotlin-test-1.9.10.jar",
            path.relativeTo(gradleLocalPath).toString().replace('\\', '/')
        )
    }

    private fun kotlinTest() = MavenDependencyImpl(
        SettingsBuilder {
            cache = {
                localRepository = gradleLocalRepository
            }
        }.settings,
        "org.jetbrains.kotlin", "kotlin-test", "1.9.10"
    )

    private fun randomString() = UUID.randomUUID().toString()
}
