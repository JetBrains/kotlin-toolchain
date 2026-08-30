/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.kotlin.plugins

import org.jetbrains.amper.frontend.api.NotBlank
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.schema.DefaultVersions

class DataFrameSettings : SchemaNode() {
    @Shorthand
    @SchemaDoc("Enables the [Kotlin Dataframe](https://kotlin.github.io/dataframe/home.html) compiler plugin")
    val enabled by value(default = false)

    @SchemaDoc("The version of the Kotlin DataFrame library to use.")
    @NotBlank
    val version by value(DefaultVersions.dataframe)
}
