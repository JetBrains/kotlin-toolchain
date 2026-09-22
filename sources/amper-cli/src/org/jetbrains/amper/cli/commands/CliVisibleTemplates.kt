/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates

/** CLI template choices, excluding Compose templates covered by the parameterized wizard. */
internal val cliVisibleTemplates: List<AmperProjectTemplate>
    get() = AmperProjectTemplates.availableTemplates.filterNot { it.id.startsWith("compose-") }
