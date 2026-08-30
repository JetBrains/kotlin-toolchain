/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.kotlin.plugins

import org.jetbrains.amper.frontend.api.NotBlank
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.schema.DefaultVersions

class KotlinxRpcSettings : SchemaNode() {
    @Shorthand
    @SchemaDoc("Enables the kotlinx.rpc compiler plugin")
    val enabled by value(false)

    @SchemaDoc("Applies the kotlinx.rpc BOM to enforce dependency version alignment")
    val applyBom by value(true)

    @SchemaDoc("The version of kotlinx.rpc to use")
    @NotBlank
    val version by value(DefaultVersions.kotlinxRpc)

    @SchemaDoc("Controls `@Rpc` [annotation type-safety](https://github.com/Kotlin/kotlinx-rpc/pull/240) " +
            "compile-time checkers.\n" +
            "CAUTION: Disabling is considered unsafe. This option is only needed to prevent cases where type-safety " +
            "analysis fails and valid code can't be compiled.")
    val annotationTypeSafetyEnabled by value(true)
}
