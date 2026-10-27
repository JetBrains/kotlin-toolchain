/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * The kind of a test filter option, without the filter value itself (which could identify the user's code).
 */
enum class FusTestFilterKind(override val fusId: String) : FusEnum<FusTestFilterKind> {
    IncludeTest("include_test"),
    IncludeClass("include_class"),
    IncludeClassPattern("include_class_pattern"),
    ExcludeClassPattern("exclude_class_pattern"),
    IncludeTag("include_tag"),
    ExcludeTag("exclude_tag"),
}
