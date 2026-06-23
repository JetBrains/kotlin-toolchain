/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import java.nio.file.Path

/**
 * Provides JVM classes, jars, or directories to add to the compile or runtime classpath of the module.
 */
internal interface ClasspathProvider {
    /**
     * Paths to classes, jars, or directories containing compile dependencies.
     */
    val compileClasspath: List<Path>
        get() = []

    /**
     * Paths to classes, jars, or directories containing runtime dependencies.
     */
    val runtimeClasspath: List<Path>
        get() = []

    val classpathElementType: ClasspathElementType
}

enum class ClasspathElementType(val order: Int) {
    LocallyCompiledClasses(order = 1),
    GeneratedClasses(order = 2),
    TransformedDependencies(order = 3),
    ExternalMavenDependencies(order = 4),
    PlatformJar(order = 5),
}
