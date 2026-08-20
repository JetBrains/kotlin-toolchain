/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.apprun

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.util.BuildType

/**
 * Defines what to run in the project.
 */
data class RunTarget(
    val module: AmperModule,
    val platform: Platform,
    val buildType: BuildType?,
)
