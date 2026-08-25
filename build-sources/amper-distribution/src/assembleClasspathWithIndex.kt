/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.io.path.clean
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

@TaskAction
fun assembleClasspathWithIndex(
    @Output outputRoot: Path,
    subdirectoryName: String,
    @Input classpath: Classpath,
    jarListFileName: String,
) {
    val targetDir = outputRoot / subdirectoryName
    targetDir.clean()
    val jarNames = copyWithDeduplication(destDir = targetDir, sourcePaths = classpath.resolvedFiles)
    targetDir.resolve(jarListFileName).writeText(jarNames.joinToString("\n"))
}
