/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * The minimum versions supported by the Kotlin Toolchain for different components.
 */
object MinVersions {
    val android: AndroidVersion = AndroidVersion(21)
    val jdk: Int = 17
    val kotlin: ComparableVersion = ComparableVersion("2.2.20")
}
