/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.native.XcodebuildTaskType

fun ProjectTasksBuilder.setupSwiftPMTasks() {
    /**
     * FIXME: Discuss what's should be the host handling
     * if (SystemInfo.CurrentHost... == macos) {
     */
    allModules()
        .filter { (module) -> module.leafPlatforms.any { it.isDescendantOf(Platform.APPLE) }  }
        .withEach {
            val resolveTaskName = ModuleTaskTypes.ResolveTransitiveSwiftPMDependencies.getTaskName(module)
            tasks.registerTask(
                task = ResolveTransitiveSwiftPMDependenciesTask(
                    module = module,
                    transitiveSwiftPMDependenciesResolver = transitiveSwiftPMDependenciesResolver(),
                    taskName = resolveTaskName,
                    taskOutputRoot = context.getTaskOutputPath(resolveTaskName)
                )
            )

            tasks.registerTask(
                task = DumpSwiftPMDependencyResolutionTask(
                    module = module,
                    taskName = ModuleTaskTypes.DumpSwiftPMDependencyResolution.getTaskName(module),
                )
            )
            tasks.registerTask(
                task = DumpKlibSignaturesTask(
                    module = module,
                    userCacheRoot = context.userCacheRoot,
                    taskName = ModuleTaskTypes.DumpKlib.getTaskName(module),
                    terminal = context.terminal,
                )
            )

            val internalPackageGenTaskName = ModuleTaskTypes.ImportSwiftPMDependenciesPackageGen.getTaskName(module)
            tasks.registerTask(
                task = InternalGenerateSwiftPMImportPackageTask(
                    module = module,
                    taskName = internalPackageGenTaskName,
                    taskOutputRoot = context.getTaskOutputPath(internalPackageGenTaskName),
                    incrementalCache = context.incrementalCache,
                )
            )

            val computeLocalPackageDependenciesTaskName = ModuleTaskTypes.ComputeLocalPackageDependencies.getTaskName(module)
            tasks.registerTask(
                task = ComputeLocalPackageInputsTask(
                    module = module,
                    taskName = computeLocalPackageDependenciesTaskName,
                    taskOutputRoot = context.getTaskOutputPath(computeLocalPackageDependenciesTaskName),
                    incrementalCache = context.incrementalCache,
                )
            )

            val fetchPackageTaskName = ModuleTaskTypes.FetchPackage.getTaskName(module)
            tasks.registerTask(
                task = FetchPackageTask(
                    module = module,
                    taskName = fetchPackageTaskName,
                    taskOutputRoot = context.getTaskOutputPath(fetchPackageTaskName),
                    incrementalCache = context.incrementalCache,
                )
            )

            module.leafAppleFragments().groupBy {
                it.platform.xcodebuildPlatform
            }.forEach { [platform, fragments] ->
                val importTaskName = XcodebuildTaskType.SwiftPMImport.getTaskName(module, platform)
                tasks.registerTask(
                    task = SwiftPMImportTask(
                        module = module,
                        platform = platform,
                        targetFragments = fragments.toSet(),
                        taskOutputRoot = context.getTaskOutputPath(importTaskName),
                        incrementalCache = context.incrementalCache,
                        taskName = importTaskName,
                        buildOutputRoot = context.buildOutputRoot,
                        userCacheRoot = context.userCacheRoot,
                        terminal = context.terminal,
                    ),
                )
            }

            if (module.type == ProductType.IOS_APP) {
                val xcodeIntegrationPackageGenTaskName = ModuleTaskTypes.XcodeIntegrationSwiftPMDependenciesPackageGen.getTaskName(module)

                tasks.registerTask(
                    task = XcodeWiredGenerateSwiftPMImportPackageTask(
                        module = module,
                        taskName = xcodeIntegrationPackageGenTaskName,
                        incrementalCache = context.incrementalCache,
                    )
                )

                tasks.registerTask(
                    task = IntegrateLinkagePackageIfNeededTask(
                        module = module,
                        taskName = ModuleTaskTypes.IntegrateSwiftPMPackageIfNeeded.getTaskName(module),
                        terminal = context.terminal,
                    ),
                )
            }
        }
}