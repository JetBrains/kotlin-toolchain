/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

/**
 * Used to fully identify a task within the task graph.
 *
 * TODO: Move back to the execution engine (`amper-cli` currently).
 *  It's only here so that plugins can substitute the actual `taskOutputDir`, but that's not really required.
 */
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank())
    }

    /**
     * Sanitized representation of this task ID that can be used as a single directory name.
     */
    val directoryName: String
        get() = value.replace(":", "_")

    companion object {
        fun moduleTask(module: AmperModule, internalName: String) =
            TaskId(":${module.userReadableName}:$internalName")
    }
}
