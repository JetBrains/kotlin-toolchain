/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import kotlin.io.path.deleteRecursively

@Deprecated("Should be dropped in favor of the CleanCommand")
internal class CleanSharedCachesCommand : AmperSubcommand(name = "clean-shared-caches") {

    override fun help(context: Context): String = "Deprecated: remove the caches that are shared between projects. Use 'clean --shared' instead."

    override val hiddenFromHelp: Boolean = true

    override suspend fun run() {
        terminal.warning("'kotlin clean-shared-caches' is deprecated and will be removed in a future release. Use 'kotlin clean --shared' instead.")

        val shareCacheDir = commonOptions.sharedCachesRoot.path
        terminal.withIndeterminateProgress("Deleting shared caches at ${shareCacheDir}…") {
            shareCacheDir.deleteRecursively()
        }
        printSuccessfulCommandConclusion("Clean successful")
    }
}
