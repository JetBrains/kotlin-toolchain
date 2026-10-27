/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

enum class FusBuildType(override val fusId: String) : FusEnum<FusBuildType> {
    Debug("debug"),
    Release("release"),
}
