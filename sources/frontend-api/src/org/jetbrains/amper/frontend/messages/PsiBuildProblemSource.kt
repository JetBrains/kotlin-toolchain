/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import java.nio.file.Path

/**
 * Location in the file designated by the node of a PSI tree.
 * Is useful for IntelliJ integration as most of its features are working over PSI, so instead of determining
 * the element at the offset, the IDE can just retrieve it as a field from the source.
 *
 * Use [PsiBuildProblemSource] factory function to construct.
 */
@Deprecated(
    "Diagnostic consumers should not assume the underlying implementation of the build problem source." +
            "Prefer using [FileBuildProblemSource] and [FileWithRangesBuildProblemSource] instead."
)
sealed interface PsiBuildProblemSource : FileBuildProblemSource {
    @UsedInIdePlugin
    val psiElement: PsiElement

    override val file: Path
        get() = psiElement.getOriginalFilePath()

    @Suppress("DEPRECATION")
    data class FileSystemLike internal constructor(
        override val psiElement: PsiFileSystemItem,
    ) : PsiBuildProblemSource

    @Suppress("DEPRECATION")
    data class Element internal constructor(
        override val psiElement: PsiElement,
        /**
         * See [org.jetbrains.amper.frontend.api.PsiTrace.rangeInElement]
         */
        val rangeInElement: IntRange? = null,
    ) : PsiBuildProblemSource, FileWithRangesBuildProblemSource {
        init {
            require(psiElement !is PsiFileSystemItem) { "PsiFileSystemItem is unexpected here" }
        }

        override val offsetRange: IntRange
            get() {
                val range = psiElement.textRange
                val startOffset = range.startOffset
                return if (rangeInElement != null) {
                    (startOffset + rangeInElement.first)..(startOffset + rangeInElement.last)
                } else IntRange(startOffset, range.endOffset)
            }
    }
}

@Suppress("DEPRECATION")
fun PsiBuildProblemSource(psiElement: PsiElement): PsiBuildProblemSource =
    if (psiElement is PsiFileSystemItem) {
        PsiBuildProblemSource.FileSystemLike(psiElement)
    } else {
        PsiBuildProblemSource.Element(psiElement)
    }

@Suppress("DEPRECATION")
fun PsiBuildProblemSource(
    psiElement: PsiElement,
    rangeInElement: IntRange,
): PsiBuildProblemSource =
    PsiBuildProblemSource.Element(psiElement, rangeInElement)
