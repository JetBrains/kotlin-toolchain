/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import java.nio.file.Path

/**
 * The result of a packaging task.
 */
interface PackageTaskResult : TaskResult {
    /**
     * The paths of the packages produced by the packaging task.
     */
    val packagePaths: List<Path>
}
