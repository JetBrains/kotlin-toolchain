/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.maven

import org.jetbrains.amper.dependency.resolution.SettingsBuilder
import org.jetbrains.amper.dependency.resolution.metadata.xml.parsePom
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maven interpolates the path of the `<file>` profile activation condition before checking it, and does not activate
 * the profile if the resulting path is relative,
 * see `org.apache.maven.model.profile.activation.FileProfileActivator`.
 */
class ProfileFileActivationTest {

    @Test
    fun `existing path interpolated from a system property activates the profile`() = runTestWithMdc {
        // 'user.dir' is always defined and points to an existing directory
        assertActivated(exists = "\${user.dir}")
    }

    @Test
    fun `existing path interpolated from a project property activates the profile`(@TempDir tempDir: Path) = runTestWithMdc {
        val existingDir = (tempDir / "natives").createDirectories()
        assertActivated(
            exists = "\${natives.dir}",
            properties = mapOf("natives.dir" to existingDir.invariantSeparatorsPathString),
        )
    }

    @Test
    fun `missing path interpolated from a system property activates the profile`() = runTestWithMdc {
        assertActivated(missing = "\${user.dir}/definitely-not-an-existing-file")
    }

    @Test
    fun `existing path interpolated from a project property does not activate the profile if it does not exist`(
        @TempDir tempDir: Path,
    ) = runTestWithMdc {
        assertNotActivated(
            exists = "\${natives.dir}",
            properties = mapOf("natives.dir" to (tempDir / "absent").invariantSeparatorsPathString),
        )
    }

    /**
     * A relative path can only be resolved against the project directory, and the pom of an external library has none.
     */
    @Test
    fun `relative path does not activate the profile`() = runTestWithMdc {
        // 'module.yaml' exists in the module directory, which is the working directory of tests
        assertNotActivated(exists = "module.yaml")
    }

    /**
     * The project directory is unknown for the pom of an external library, so Maven refuses to interpolate the path.
     */
    @Test
    fun `path referencing the project directory does not activate the profile`() = runTestWithMdc {
        assertNotActivated(exists = "\${basedir}")
        assertNotActivated(missing = "\${basedir}/definitely-not-an-existing-file")
    }

    @Test
    fun `path referencing an undefined property does not activate the profile`() = runTestWithMdc {
        assertNotActivated(exists = "\${undefined.property.name}")
    }

    private suspend fun assertActivated(
        exists: String? = null,
        missing: String? = null,
        properties: Map<String, String> = emptyMap(),
    ) = assertTrue(
        isProfileActivated(exists, missing, properties),
        "The profile activated by ${if (exists != null) "existing '$exists'" else "missing '$missing'"} " +
                "was expected to be activated",
    )

    private suspend fun assertNotActivated(
        exists: String? = null,
        missing: String? = null,
        properties: Map<String, String> = emptyMap(),
    ) = assertFalse(
        isProfileActivated(exists, missing, properties),
        "The profile activated by ${if (exists != null) "existing '$exists'" else "missing '$missing'"} " +
                "was not expected to be activated",
    )

    private suspend fun isProfileActivated(
        exists: String?,
        missing: String?,
        properties: Map<String, String>,
    ): Boolean {
        val project = pomWithFileActivatedProfile(exists, missing, properties).parsePom()
        val profile = project.profiles?.profiles?.single { it.id == FILE_ACTIVATED_PROFILE_ID }
        return profile?.isActivated(SettingsBuilder().settings, project) == true
    }

    private fun pomWithFileActivatedProfile(
        exists: String?,
        missing: String?,
        properties: Map<String, String>,
    ) = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>file-activation</artifactId>
            <version>1.0</version>
            <properties>
                ${properties.entries.joinToString("\n") { "<${it.key}>${it.value}</${it.key}>" }}
            </properties>
            <profiles>
                <profile>
                    <id>$FILE_ACTIVATED_PROFILE_ID</id>
                    <activation>
                        <file>
                            ${exists?.let { "<exists>$it</exists>" } ?: ""}
                            ${missing?.let { "<missing>$it</missing>" } ?: ""}
                        </file>
                    </activation>
                </profile>
            </profiles>
        </project>
    """.trimIndent()
}

private const val FILE_ACTIVATED_PROFILE_ID = "file-activated"
