/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.ProjectFilesChanges
import com.jetbrains.cidr.xcode.model.XCSwiftPackageProductDependency
import com.jetbrains.cidr.xcode.pbxproj.PbxId
import com.jetbrains.cidr.xcode.util.XcodeUserDataHolder
import fleet.com.intellij.openapi.util.UserDataHolderEx
import org.jetbrains.amper.cli.commands.IntegrateLinkagePackageCommand
import org.jetbrains.amper.cli.commands.tools.XCodeIntegrationCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.ios.initializeXcodeComponentManager
import org.jetbrains.amper.tasks.ios.xcodeprojPath
import org.jetbrains.amper.tasks.native.reportXcodeError
import java.nio.file.Path

class CheckSwiftPMImportIsWiredWithXcodeProjectTask(
    val module: AmperModule,
    val projectRootPath: Path,
    override val taskName: TaskName,
    private val transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
) : Task {

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val xcodeProjectThatCalledEmbedAndSign = module.xcodeprojPath()
        if (!transitiveSwiftPMDependenciesResolver.resolve().hasDirectOrTransitiveSwiftPMDependencies) return EmptyTaskResult

        checkIfTheLinkageProjectIsConnectedToTheXcodeProject(
            xcodeProjectThatCalledEmbedAndSign,
            XCodeIntegrationCommand.COMMAND_NAME,
        )

        return EmptyTaskResult
    }

    private class XcodeProjectHandle : XcodeProjectId, UserDataHolderEx by XcodeUserDataHolder()

    internal suspend fun checkIfTheLinkageProjectIsConnectedToTheXcodeProject(
        xcodeProjectThatCalledEmbedAndSign: Path,
        integrationName: String,
    ) {
        initializeXcodeComponentManager()
        val xcodeProject = PBXProjectFile(XcodeProjectHandle(), xcodeProjectThatCalledEmbedAndSign, xcodeProjectThatCalledEmbedAndSign.resolve("project.pbxproj"))
        xcodeProject.load(ProjectFilesChanges())

        val linkageProducts = linkageProductsReferencedInPBXObjects(xcodeProject)
        val hasSyntheticImportProjectReference = linkageProducts.isNotEmpty()
        if (!hasSyntheticImportProjectReference) {
            val command =
                "'${ProjectCliContext.wrapperScriptPath}' '${IntegrateLinkagePackageCommand.COMMAND_NAME}' --module '${module.userReadableName}'"

            val messageLines = listOf(
                "You have SwiftPM dependencies with $integrationName integration.",
                "Please integrate with synthetic import linkage project by",
                "running the following command:",
                command
            )

            // Report plain text to the console, so that it is visible in the Xcode build log.
            messageLines.forEach { it.reportXcodeError() }

            error(messageLines.joinToString("\n"))
        }
    }
}

internal fun linkageProductsReferencedInPBXObjects(project: PBXProjectFile): Set<PbxId> {
    // FIXME: KT-83876 Check if the product is correctly integrated into the build phase
    return project.objects.mapNotNull { pbxObject ->
        if ((pbxObject as? XCSwiftPackageProductDependency)?.productName == SwiftPMImportTask.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME) {
            pbxObject.id
        } else {
            null
        }
    }.toSet()
}