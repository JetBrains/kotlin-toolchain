/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * The kind of publication performed by the `publish` command.
 */
enum class FusPublicationType(override val fusId: String) : FusEnum<FusPublicationType> {
    Maven("maven"),
    Ios("ios"),
}
