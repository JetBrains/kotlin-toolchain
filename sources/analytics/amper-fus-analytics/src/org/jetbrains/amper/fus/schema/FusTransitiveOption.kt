/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * The state of the `--transitive`/`--non-transitive` CLI option of the `publish` command.
 */
enum class FusTransitiveOption(override val fusId: String) : FusEnum<FusTransitiveOption> {
    Enabled("true"),
    Disabled("false"),

    /**
     * Neither option was passed on the command line, so the transitivity is decided in another way.
     */
    Auto("auto"),
}
