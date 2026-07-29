/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import kotlin.io.path.Path
import kotlin.io.path.outputStream

class DumpSwiftPMDependencyResolution(
    override val taskName: TaskName,
    private val transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        Path(System.getenv("SWIFTPM_RESOLUTION_DUMP_PATH")).outputStream().use {
            json.encodeToStream(transitiveSwiftPMDependenciesResolver.resolve(), it)
        }
        return EmptyTaskResult
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
            allowStructuredMapKeys = true
        }
    }
}