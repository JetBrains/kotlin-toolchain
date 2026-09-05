/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import org.jetbrains.amper.frontend.getLineAndColumnRangeInDocument
import org.jetbrains.amper.problems.reporting.FileWithLineColumnProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.LineAndColumnRange

/**
 * Range of problematic code expressed in terms of lines and columns.
 * Can be used by clients to render the links to the exact location in the file or display an erroneous part of the
 * code.
 */
fun FileWithRangesBuildProblemSource.computeRange(): LineAndColumnRange {
    if (this is FileWithLineColumnProblemSource)
        return lineColumnRange

    val document = when (this) {
        is PsiBuildProblemSource -> psiElement.containingFile.viewProvider.document
        else -> runReadActionBlocking {  // Fixme: do this via FrontendPathResolver?
            VirtualFileManager.getInstance().findFileByNioPath(file)
        }?.findDocument()
    }

    return getLineAndColumnRangeInDocument(document, offsetRange)
}
