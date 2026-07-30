/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.swiftpm.SwiftPMDependencies
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class SwiftPMDependenciesArtifact(
    override val path: Path,
    val module: AmperModule
) : Artifact {
    @OptIn(ExperimentalSerializationApi::class)
    val swiftPMDependencies: SwiftPMDependencies by lazy {
        path.inputStream().use {
            swiftPMJson.decodeFromStream(it)
        }
    }
}

fun swiftPMDependenciesArtifact(module: AmperModule) = ArtifactSelector(
    type = ArtifactType(SwiftPMDependenciesArtifact::class),
    predicate = { it.module == module },
    description = "SwiftPMDependencies",
    quantifier = Quantifier.Single,
)

class ResolveTransitiveSwiftPMDependenciesTask(
    val module: AmperModule,
    override val taskName: TaskName,
    private val taskOutputRoot: TaskOutputRoot,
    private val transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
) : ArtifactTaskBase() {
    private val artifact by SwiftPMDependenciesArtifact(taskOutputRoot.path.resolve("swiftPMDependencies.json"), module)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        artifact.path.parent.createDirectories()
        artifact.path.outputStream().use {
            @OptIn(ExperimentalSerializationApi::class)
            swiftPMJson.encodeToStream(transitiveSwiftPMDependenciesResolver.resolve(), it)
        }
        return EmptyTaskResult
    }
}