/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.lazyload

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * A classpath that is not loaded as part of the Amper CLI's own classpath, but provided in the distribution to be
 * loaded lazily (in a separate classloader or process) when needed.
 *
 * All the jars of these classpaths are deduplicated in a single `extra/jars` directory of the distribution, and each
 * classpath is described by a `lazy/<name>.classpath.txt` index file listing the jars it consists of.
 *
 * This list comes from `amper-cli`'s `module.yaml`.
 */
internal enum class ExtraClasspath(private val classpathName: String) {
    PLUGINS_PROCESSOR(classpathName = "plugins-processor"),
    EXTENSIBILITY_API(classpathName = "extensibility-api"),
    AMPER_JIC_RUNNER(classpathName = "amper-jic-runner"),
    ANDROID_INTEGRATION_GRADLE_PLUGIN(classpathName = "android-integration-gradle-plugin"),
    KOTLIN_BUILD_TOOLS_COMPAT(classpathName = "kotlin-build-tools-compat");

    private val distRoot by lazy {
        Path(checkNotNull(System.getenv("KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR")) {
            "Missing `KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR` env var. Ensure your wrapper script integrity."
        })
    }

    private val extraDir get() = distRoot / "extra"

    /**
     * Returns the list of jars that belong to this [ExtraClasspath] from the Amper distribution.
     */
    fun findJarsInDistribution(): List<Path> {
        val indexFile = extraDir / "$classpathName.classpath.txt"
        check(indexFile.exists()) {
            "Missing classpath index file at $indexFile. Ensure your Kotlin Toolchain distribution integrity."
        }
        val jarsDir = extraDir / "jars"
        return indexFile.readLines()
            .filter { it.isNotBlank() }
            .map { jarName -> jarsDir / jarName }
            // the classpath order from the index file is preserved, except for naughty jars (sortedBy is stable)
            .sortedBy { it.isNaughtyJar() }
    }
}

/**
 * These jars embed some non-shaded dependencies and, as such, will hijack the classloading of 3rd party classes
 * if they are in these dependencies. For example, kotlin-compiler-2.3.0.jar contains opentelemetry classes that
 * will be loaded instead of the correct ones if the kotlin-compiler jar is first in the classpath.
 */
private val prefixesOfNaughtyJarsThatShouldBeLast = listOf("kotlin-compiler-", "analysis-api-")

private fun Path.isNaughtyJar(): Boolean = prefixesOfNaughtyJarsThatShouldBeLast.any { name.startsWith(it) }
