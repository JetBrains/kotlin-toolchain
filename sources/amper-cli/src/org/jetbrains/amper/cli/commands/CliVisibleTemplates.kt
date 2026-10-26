/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates

/** The template that the parameterized Kotlin Multiplatform wizard generates from, instead of copying it as is. */
internal const val KotlinMultiplatformTemplateId = "kotlin-multiplatform-app"

/**
 * The static templates that the CLI can copy as is: this excludes the Compose templates meant for the IDE, and the
 * [KotlinMultiplatformTemplateId] template, which is covered by the parameterized wizard.
 */
internal val cliVisibleTemplates: List<AmperProjectTemplate>
    get() = AmperProjectTemplates.availableTemplates.filterNot {
        it.id.startsWith("compose-") || it.id == KotlinMultiplatformTemplateId
    }
