/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * The outcome of the execution of a CLI command.
 */
enum class FusCommandResult(override val fusId: String) : FusEnum<FusCommandResult> {
    Success("success"),
    Failure("failure"),

    /**
     * The command didn't complete because the user canceled it, or because it was interrupted.
     * This is not counted as a failure, because nothing actually went wrong.
     */
    Canceled("canceled"),
}
