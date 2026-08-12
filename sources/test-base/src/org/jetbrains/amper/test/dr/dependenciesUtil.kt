/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.dr

import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext

fun String.toMavenNode(context: Context): MavenDependencyNodeWithContext {
    val isBom = startsWith("bom:")
    val coordinates = removePrefix("bom:").trim().toMavenCoordinates()
    return coordinates.toMavenNode(context, isBom)
}

fun String.toMavenCoordinates(): MavenCoordinates {
    val parts = split(":")
    val packagingType = parts.last().substringAfter("@", "").takeIf { it.isNotEmpty() && parts.size > 1 }
    val partsWithoutPackagingType = parts.mapIndexed { index, part ->
        if (index == parts.lastIndex && packagingType != null) part.substringBefore("@") else part
    }
    val group = partsWithoutPackagingType[0]
    val module = partsWithoutPackagingType[1]
    val version = if (partsWithoutPackagingType.size > 2) partsWithoutPackagingType[2] else null
    val classifier = if (partsWithoutPackagingType.size > 3) partsWithoutPackagingType[3] else null
    return MavenCoordinates(group, module, version, classifier = classifier, packagingType = packagingType)
}

fun MavenCoordinates.toMavenNode(context: Context, isBom: Boolean = false): MavenDependencyNodeWithContext {
    return context.toMavenDependencyNode( this, isBom = isBom)
}

