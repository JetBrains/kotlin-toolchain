/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.metadata.xml

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Merging of the `dependencies` sections coming from different sources (active profiles, the pom itself, parent poms)
 * must deduplicate entries by the same key Maven uses, namely `groupId:artifactId:type:classifier`.
 */
class DependenciesMergeTest {

    @Test
    fun `dependencies with different classifiers are both kept`() {
        val lwjgl = dependency("org.lwjgl", "lwjgl")
        val lwjglNatives = dependency("org.lwjgl", "lwjgl", classifier = "natives-linux")

        assertEquals(
            listOf(lwjgl, lwjglNatives),
            (Dependencies(listOf(lwjgl)) + Dependencies(listOf(lwjglNatives))).dependencies,
        )
    }

    @Test
    fun `dependencies with different types are both kept`() {
        val jar = dependency("org.example", "foo")
        val testJar = dependency("org.example", "foo", type = "test-jar")

        assertEquals(
            listOf(jar, testJar),
            (Dependencies(listOf(jar)) + Dependencies(listOf(testJar))).dependencies,
        )
    }

    @Test
    fun `duplicated dependencies are merged keeping the one closest to the original pom`() {
        val fromPom = dependency("org.example", "foo", version = "1.0")
        val fromParent = dependency("org.example", "foo", version = "2.0")

        assertEquals(
            listOf(fromPom),
            (Dependencies(listOf(fromPom)) + Dependencies(listOf(fromParent))).dependencies,
        )
    }

    @Test
    fun `omitted type is treated as jar`() {
        val implicitJar = dependency("org.example", "foo", version = "1.0")
        val explicitJar = dependency("org.example", "foo", version = "2.0", type = "jar")

        assertEquals(
            listOf(implicitJar),
            (Dependencies(listOf(implicitJar)) + Dependencies(listOf(explicitJar))).dependencies,
        )
    }

    @Test
    fun `dependencyManagement entries with different classifiers are both kept`() {
        val lwjgl = dependency("org.lwjgl", "lwjgl", version = "3.3.5")
        val lwjglNatives = dependency("org.lwjgl", "lwjgl", version = "3.3.5", classifier = "natives-linux")

        val merged = DependencyManagement(Dependencies(listOf(lwjgl))) +
                DependencyManagement(Dependencies(listOf(lwjglNatives)))

        assertEquals(listOf(lwjgl, lwjglNatives), merged.dependencies?.dependencies)
    }

    private fun dependency(
        groupId: String,
        artifactId: String,
        version: String? = null,
        type: String? = null,
        classifier: String? = null,
    ) = Dependency(groupId = groupId, artifactId = artifactId, version = version, type = type, classifier = classifier)
}
