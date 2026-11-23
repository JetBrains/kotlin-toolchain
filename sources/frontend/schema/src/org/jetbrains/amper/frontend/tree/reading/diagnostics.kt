/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.SchemaVariantDeclaration
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls

class MissingValue(
    override val source: FileBuildProblemSource,
    @UsedInIdePlugin
    val expectedType: SchemaType,
    typeString: String,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.Generic

    override val diagnosticId get() = TreeDiagnosticId.MissingValue
    override val message = SchemaBundle.message("validation.structure.missing.value", typeString)
}

class InvalidTaskActionType(
    override val source: FileBuildProblemSource,
    val invalidType: String,
    val taskActionType: SchemaVariantDeclaration,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.UnknownSymbol

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.InvalidTaskActionType
    override val message: @Nls String by lazy {
        SchemaBundle.message("validation.types.tag.task.action.invalid", invalidType, formatAvailableTasks(taskActionType))
    }
}

class MissingTaskActionType(
    override val source: FileBuildProblemSource,
    val taskActionType: SchemaVariantDeclaration,
) : BuildProblem {
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.Generic

    override val diagnosticId: DiagnosticId = TreeDiagnosticId.MissingTaskActionType

    override val message: @Nls String by lazy {
        SchemaBundle.message("validation.types.tag.task.action.missing", formatAvailableTasks(taskActionType))
    }
}

private fun formatAvailableTasks(type: SchemaVariantDeclaration): String {
    val string = if (type.variants.isEmpty())
        "`<none>`" else type.variants.joinToString { "`${it.displayName}`" }
    return string
}