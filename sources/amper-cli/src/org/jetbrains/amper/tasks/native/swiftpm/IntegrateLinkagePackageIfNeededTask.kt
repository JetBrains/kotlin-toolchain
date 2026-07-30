/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.ios.xcodeProjectPath

/**
 * This task runs only if the build happens under Xcode build
 */
class IntegrateLinkagePackageIfNeededTask(
    val module: AmperModule,
    override val taskName: TaskName,
    private val terminal: Terminal,
) : ArtifactTaskBase() {
    private val swiftPMDependenciesArtifact by swiftPMDependenciesArtifact(module)
    private val generatedPackage by generatedPackage<XcodeWiredSwiftPMImportPackage>(module)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        checkAndIntegrateXcodeProjectWithSwiftPMPackageIfNeeded(
            swiftPMDependenciesArtifact,
            module.xcodeProjectPath,
            terminal
        )
        return EmptyTaskResult
    }
}
