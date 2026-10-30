/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import org.jetbrains.amper.agentdetection.detectAgent
import org.jetbrains.amper.cidetection.CISystem
import org.jetbrains.amper.fus.schema.FusCommandCallerType
import org.jetbrains.amper.fus.schema.FusIdeCode

object FusEnvironmentDetection {

    val ciSystem by lazy { CISystem.detect() }

    val aiAgent by lazy { detectAgent() }

    val ide by lazy {
        when(System.getenv("KOTLIN_TOOLCHAIN_FUS_CALLING_IDE_PRODUCT_CODE")?.takeIf { it.isNotBlank() }) {
            null -> null
            "IU" -> FusIdeCode.IntelliJUltimate
            "IC" -> FusIdeCode.IntelliJCommunity
            "AI" -> FusIdeCode.AndroidStudio
            else -> FusIdeCode.Other
        }
    }

    val callerType by lazy {
        when {
            ide != null -> FusCommandCallerType.Ide
            aiAgent != null -> FusCommandCallerType.AIAgent
            ciSystem != null -> FusCommandCallerType.CI
            else -> FusCommandCallerType.Terminal
        }
    }
}
