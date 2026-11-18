/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.attributes

/**
 * The suffix added to the published Gradle metadata variant names.
 * It is an implementation detail and is a subject to change,
 * see https://docs.google.com/document/d/18MsmrX2iYuoKS3HvY-_xnk_SMenLQzSPArj4yuy1Hfw/edit?tab=t.0
 */
const val PUBLISHED_SUFFIX = "-published"

/**
 * The usage kinds of the published variants.
 * The full variant name includes this part, i.e.,`iosArm64ApiElements-published`.
 */
enum class PublishedUsage(val variantNamePart: String) {
    API("ApiElements"),
    RUNTIME("RuntimeElements"),
    SOURCES("SourcesElements"),
    RESOURCES("ResourcesElements"),
}

/**
 * The full published variant name (see [PublishedUsage]).
 */
fun publishedVariantName(targetName: String, usage: PublishedUsage): String =
    "$targetName${usage.variantNamePart}$PUBLISHED_SUFFIX"

/**
 * The target name a full published variant name starts with (see [PublishedUsage]),
 * or `null` if the name is not a published variant name of a target.
 */
fun publishedVariantTargetName(variantName: String): String? {
    val nameWithoutSuffix = variantName.removeSuffix(PUBLISHED_SUFFIX)
    val usage = PublishedUsage.entries.firstOrNull { nameWithoutSuffix.endsWith(it.variantNamePart) } ?: return null
    return nameWithoutSuffix.removeSuffix(usage.variantNamePart)
}

/**
 * @return platform-specific suffix of the moduleId used by KTC/KGP for the publication of platform specific library.
 * In case of KAR publication it goes to the capabilities declared for KAR-based variants
 */
fun platformModuleTargetName(targetName: String): String =
    when (targetName) {
        "wasmJs" -> "wasm-js"
        "wasmWasi" -> "wasm-wasi"
        else -> targetName.lowercase()
    }
