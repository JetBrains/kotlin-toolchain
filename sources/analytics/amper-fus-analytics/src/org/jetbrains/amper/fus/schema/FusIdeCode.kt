/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

enum class FusIdeCode(override val fusId: String) : FusEnum<FusIdeCode> {
    IntelliJUltimate("idea-ultimate"),
    IntelliJCommunity("idea-community"),
    AndroidStudio("android-studio"),
    Other("other")
}
