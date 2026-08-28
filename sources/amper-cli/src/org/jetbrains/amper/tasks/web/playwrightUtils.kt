/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import com.microsoft.playwright.Playwright
import kotlinx.serialization.json.Json

/**
 * The Playwright version used both for the JVM library (from the version catalog) and for the CLI installed via pnpm.
 *
 * We read it from the Playwright driver bundled inside the `com.microsoft.playwright` jars so that the pnpm-installed
 * CLI can never drift from the library on the classpath.
 */
internal val PLAYWRIGHT_VERSION: String by lazy {
    val resource = "/driver/package/package.json"
    val text = Playwright::class.java.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Cannot find Playwright driver descriptor '$resource' on the classpath")

    val packageJson: PackageJson = json.decodeFromString(text)

    packageJson.version
}

private val json = Json {
    ignoreUnknownKeys = true
}