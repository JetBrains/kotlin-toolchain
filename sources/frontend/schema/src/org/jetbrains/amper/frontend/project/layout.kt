/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.project

import org.jetbrains.amper.frontend.TaskId
import java.nio.file.Path
import kotlin.io.path.div


fun AmperProjectContext.getTaskOutputRoot(taskId: TaskId): Path {
    return projectBuildDir / "tasks" / taskId.directoryName
}