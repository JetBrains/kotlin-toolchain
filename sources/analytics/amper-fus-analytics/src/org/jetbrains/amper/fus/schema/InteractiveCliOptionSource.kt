/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

enum class InteractiveCliOptionSource(override val fusId: String) : FusEnum<InteractiveCliOptionSource> {
    ExplicitCliOption("cli_option"),
    AskedInteractively("prompted"),
    Inferred("inferred"),
}

enum class ComposeHotReloadCliOptionSource(override val fusId: String) : FusEnum<ComposeHotReloadCliOptionSource> {
    EnabledByDefault("enabled_by_default"),
    EnabledExplicitly("enabled_explicitly"),
    DisabledExplicitly("disabled_explicitly"),
    NotApplicable("not_applicable")
}
