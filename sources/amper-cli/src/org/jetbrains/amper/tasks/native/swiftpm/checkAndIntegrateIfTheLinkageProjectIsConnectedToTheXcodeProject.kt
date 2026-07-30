/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.ProjectFilesChanges
import com.jetbrains.cidr.xcode.model.XCSwiftPackageProductDependency
import com.jetbrains.cidr.xcode.model.saveProperly
import com.jetbrains.cidr.xcode.pbxproj.PbxId
import com.jetbrains.cidr.xcode.util.XcodeUserDataHolder
import fleet.com.intellij.openapi.util.UserDataHolderEx
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.tasks.ios.initializeXcodeComponentManager
import org.jetbrains.amper.tasks.native.swiftpm.GenerateSwiftPMImportPackageTask.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME
import java.nio.file.Path

private class XcodeProjectHandle : XcodeProjectId, UserDataHolderEx by XcodeUserDataHolder()

internal suspend fun checkAndIntegrateXcodeProjectWithSwiftPMPackageIfNeeded(
    swiftPMDependenciesArtifact: SwiftPMDependenciesArtifact,
    xcodeProjectPath: Path,
    terminal: Terminal,
) {
    if (!swiftPMDependenciesArtifact.swiftPMDependencies.hasDirectOrTransitiveSwiftPMDependencies) return
    initializeXcodeComponentManager()
    val xcodeProject = PBXProjectFile(XcodeProjectHandle(), xcodeProjectPath, xcodeProjectPath.resolve("project.pbxproj"))
    xcodeProject.load(ProjectFilesChanges())
    xcodeProject.lock()

    val linkageProducts = linkageProductsReferencedInPBXObjects(xcodeProject)
    val hasSyntheticImportProjectReference = linkageProducts.isNotEmpty()
    if (!hasSyntheticImportProjectReference) {
        val messageLines = listOf(
            "Your project uses SwiftPM dependencies",
            "Xcode project has been integrated with Kotlin-managed SwiftPM package",
            "Please rebuild the project",
        )

        integrateSwiftPMPackageIfNeeded(swiftPMDependenciesArtifact, project = xcodeProject, terminal = terminal)

        xcodeProject.saveProperly()

        messageLines.forEach { terminal.reportXcodeError(it) }

        userReadableError(messageLines.joinToString("\n"))
    }
}

internal fun linkageProductsReferencedInPBXObjects(project: PBXProjectFile): Set<PbxId> {
    // FIXME: KT-83876 Check if the product is correctly integrated into the build phase
    return project.objects.mapNotNull { pbxObject ->
        if ((pbxObject as? XCSwiftPackageProductDependency)?.productName == SYNTHETIC_IMPORT_TARGET_MAGIC_NAME) {
            pbxObject.id
        } else {
            null
        }
    }.toSet()
}