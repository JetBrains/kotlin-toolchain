/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.maven

import org.codehaus.plexus.util.Os
import org.jetbrains.amper.dependency.resolution.SettingsBuilder
import org.jetbrains.amper.dependency.resolution.metadata.xml.parsePom
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import uk.org.webcompere.systemstubs.properties.SystemProperties
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maven checks the `<os>` profile activation condition against the current host,
 * see `org.apache.maven.model.profile.activation.OperatingSystemProfileActivator`.
 */
class ProfileOsActivationTest {

    /**
     * A host belongs to several OS families at once (macOS is both `mac` and `unix`), and Maven activates a profile
     * for every one of them because it asks [Os.isFamily]. Comparing the expected family to the single
     * [Os.OS_FAMILY] string instead only ever matches one family per host, silently dropping the others.
     *
     * This assertion is host-independent on purpose: it states the contract for whatever host runs it, so it holds on
     * hosts where the two behaviors happen to coincide (Linux, Windows) and catches the ones where they don't (macOS).
     */
    @Test
    fun `every family the host belongs to activates the profile`() = runTestWithMdc {
        val matchingFamilies = Os.getValidFamilies().filter { Os.isFamily(it) }

        // guards the test itself: an empty list would make all the assertions below vacuous
        assertTrue(matchingFamilies.isNotEmpty(), "The host must belong to at least one of ${Os.getValidFamilies()}")

        for (family in Os.getValidFamilies()) {
            val hostBelongsToFamily = family in matchingFamilies
            assertEquals(
                expected = hostBelongsToFamily,
                actual = isProfileActivated(family = family),
                message = "Family '$family' (host families: $matchingFamilies, Os.OS_FAMILY: '${Os.OS_FAMILY}')",
            )
        }
    }

    @Test
    fun `negated family activates the profile for every family the host does not belong to`() = runTestWithMdc {
        for (family in Os.getValidFamilies()) {
            assertEquals(
                expected = !Os.isFamily(family),
                actual = isProfileActivated(family = "!$family"),
                message = "Negated family '!$family' (Os.OS_FAMILY: '${Os.OS_FAMILY}')",
            )
        }
    }

    @Test
    fun `unknown family does not activate the profile`() = runTestWithMdc {
        assertFalse(isProfileActivated(family = "definitely-not-an-os-family"))
    }

    @Test
    fun `matching name, arch and version activate the profile`() = runTestWithMdc {
        assertTrue(isProfileActivated(name = Os.OS_NAME))
        assertTrue(isProfileActivated(arch = Os.OS_ARCH))
        assertTrue(isProfileActivated(version = Os.OS_VERSION))
    }

    @Test
    fun `non-matching name, arch and version do not activate the profile`() = runTestWithMdc {
        assertFalse(isProfileActivated(name = "${Os.OS_NAME}-nope"))
        assertFalse(isProfileActivated(arch = "${Os.OS_ARCH}-nope"))
        assertFalse(isProfileActivated(version = "${Os.OS_VERSION}-nope"))
    }

    /**
     * All the declared conditions must match, and the family is one of them: a profile requesting a family the host
     * belongs to together with a foreign architecture must stay inactive.
     */
    @Test
    fun `all declared conditions must match`() = runTestWithMdc {
        val hostFamily = Os.getValidFamilies().first { Os.isFamily(it) }
        assertTrue(isProfileActivated(family = hostFamily, arch = Os.OS_ARCH))
        assertFalse(isProfileActivated(family = hostFamily, arch = "${Os.OS_ARCH}-nope"))
    }

    @Test
    fun `empty os condition does not activate the profile`() = runTestWithMdc {
        assertFalse(isProfileActivated())
    }

    /**
     * The family a host belongs to is derived from `os.name`, so an activation condition declaring only `<family>`
     * must still contribute that property to the incremental cache key. Otherwise, a resolution cached on one OS is
     * silently reused on another one where other profiles are active — the architecture alone doesn't identify a host,
     * it matches across Linux x64 and Windows x64, for instance.
     */
    @Test
    @ExtendWith(SystemStubsExtension::class)
    fun `family activation contributes the OS to the incremental cache key`(
        @TempDir stateRoot: Path,
        systemProperties: SystemProperties,
    ) = runTestWithMdc {
        val cache = IncrementalCache(stateRoot = stateRoot, codeVersion = "1")
        var activationChecks = 0

        suspend fun checkActivationInsideCachedBlock() {
            cache.execute(key = "os-family-activation", inputValues = emptyMap(), inputFiles = emptyList()) {
                isProfileActivated(family = "unix")
                activationChecks++
                IncrementalCache.ExecutionResult(outputFiles = emptyList())
            }
        }

        checkActivationInsideCachedBlock()
        checkActivationInsideCachedBlock()
        assertEquals(
            expected = 1,
            actual = activationChecks,
            message = "Nothing has changed, the second run was expected to be served from the cache",
        )

        systemProperties.set("os.name", "Some Other OS")

        checkActivationInsideCachedBlock()
        assertEquals(
            expected = 2,
            actual = activationChecks,
            message = "The cached activation result was expected to be invalidated by the change of 'os.name'",
        )
    }

    private suspend fun isProfileActivated(
        name: String? = null,
        family: String? = null,
        arch: String? = null,
        version: String? = null,
    ): Boolean {
        val project = pomWithOsActivatedProfile(name, family, arch, version).parsePom()
        val profile = project.profiles?.profiles?.single { it.id == OS_ACTIVATED_PROFILE_ID }
        return profile?.isActivated(SettingsBuilder().settings, project) == true
    }

    private fun pomWithOsActivatedProfile(
        name: String?,
        family: String?,
        arch: String?,
        version: String?,
    ) = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>os-activation</artifactId>
            <version>1.0</version>
            <profiles>
                <profile>
                    <id>$OS_ACTIVATED_PROFILE_ID</id>
                    <activation>
                        <os>
                            ${name?.let { "<name>$it</name>" } ?: ""}
                            ${family?.let { "<family>$it</family>" } ?: ""}
                            ${arch?.let { "<arch>$it</arch>" } ?: ""}
                            ${version?.let { "<version>$it</version>" } ?: ""}
                        </os>
                    </activation>
                </profile>
            </profiles>
        </project>
    """.trimIndent()
}

private const val OS_ACTIVATED_PROFILE_ID = "os-activated"
