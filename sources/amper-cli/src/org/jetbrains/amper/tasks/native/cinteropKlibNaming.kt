/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.publishingSettings
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

private const val CINTEROP_INFIX = "-cinterop-"

/**
 * The name that identifies the cinterop library named [cinteropName] of this module.
 *
 * This ends up as `unique_name` in the klib manifest, and the Kotlin compiler uses it to tell libraries apart on the
 * compilation classpath. It therefore has to be qualified with the publication coordinates: the bare cinterop name
 * comes from a `.def` file name, and nothing prevents two dependencies from both declaring a `libcurl` interop.
 *
 * Follows the KGP convention, e.g. `org.jetbrains.kotlinx:atomicfu-cinterop-interop`. The group is omitted when the
 * module has no publishing group configured, just like KGP omits an empty Gradle project group.
 */
internal fun AmperModule.cinteropKlibModuleName(cinteropName: String): String {
    val baseName = "${publishingSettings.artifactId ?: userReadableName}$CINTEROP_INFIX$cinteropName"
    val group = publishingSettings.group
    return if (group.isNullOrBlank()) baseName else "$group:$baseName"
}

/**
 * [cinteropKlibModuleName] made usable as a file name, the same way the commonizer names its output directories.
 *
 * This is what KGP-published libraries look like inside their all-metadata JAR
 * (e.g. `org.jetbrains.kotlinx_atomicfu-cinterop-interop`).
 */
internal fun AmperModule.cinteropKlibBaseName(cinteropName: String): String =
    cinteropKlibModuleName(cinteropName).replace(':', '_')

/**
 * The file name of the klib produced for the cinterop library named [cinteropName] of this module.
 */
internal fun AmperModule.cinteropKlibFileName(cinteropName: String): String =
    "${cinteropKlibBaseName(cinteropName)}.klib"

/**
 * Extracts back the plain cinterop name (the `.def` file name) from a klib file or directory produced for it.
 *
 * Works both for klibs produced by [cinteropKlibFileName] and for the ones found in KGP-published libraries, since
 * both use the same `…-cinterop-<name>` convention. Also accepts the `.klib.failed` markers.
 */
internal fun Path.cinteropName(): String = fileName.toString()
    .removeSuffix(".failed")
    .removeSuffix(".klib")
    .substringAfterLast(CINTEROP_INFIX)
