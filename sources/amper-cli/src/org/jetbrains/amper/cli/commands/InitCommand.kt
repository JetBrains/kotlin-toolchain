/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.io.path.Path

internal class InitCommand : AbstractNewProjectCommand(name = "init") {

    private val targetDir: Path? by option(
        "--target-dir",
        help = "An existing directory to generate the project in. Defaults to the current directory.",
    ).path(canBeFile = false, mustExist = true)

    override fun help(context: Context): String = "Initialize a Kotlin project in an existing directory (the current directory by default)"

    override suspend fun resolveTargetDir(): Path = targetDir ?: Path(System.getProperty("user.dir"))
}
