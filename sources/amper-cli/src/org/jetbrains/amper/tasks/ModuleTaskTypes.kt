/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.frontend.doCapitalize

/**
 * Module-wide task types
 */
sealed class ModuleTaskTypes(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.Module {

    // ***** Plugins *****

    data object BuildAmperPluginInfo : ModuleTaskTypes(
        internalName = "buildAmperPluginInfo",
        operationMoniker = "building plugin info",
    )

    // ***** Cinterop *****

    data object CommonizeCinterop : ModuleTaskTypes(
        internalName = "commonizeCinterop",
        operationMoniker = "commonizing cinterop definitions",
    )

    // ***** iOS *****

    data object ManageXCodeProject : ModuleTaskTypes(
        internalName = "manageXCodeProject",
        operationMoniker = "working with the Xcode project",
    )

    data object ResolveTransitiveSwiftPMDependencies : ModuleTaskTypes(
        internalName = "resolveTransitiveSwiftPMDependencies",
        operationMoniker = "resolving SwiftPM dependencies",
    )

    data object DumpSwiftPMDependencyResolution : ModuleTaskTypes(
        internalName = "dumpSwiftPMDependencyResolution",
        operationMoniker = "dumping SwiftPM dependency resolution",
    )

    data object DumpKlib : ModuleTaskTypes(
        internalName = "dumpKlib",
        operationMoniker = "dumping klib signatures",
    )

    data object ImportSwiftPMDependenciesPackageGen : ModuleTaskTypes(
        internalName = "internalSwiftPMImportPackageGen",
        operationMoniker = "generating SwiftPM import package",
    )

    data object XcodeIntegrationSwiftPMDependenciesPackageGen : ModuleTaskTypes(
        internalName = "xcodeIntegrationSwiftPMImportPackageGen",
        operationMoniker = "generating SwiftPM import package for Xcode integration",
    )

    data object IntegrateSwiftPMPackageIfNeeded : ModuleTaskTypes(
        internalName = "integrateLinkagePackage",
        operationMoniker = "integrating Kotlin-managed SwiftPM project with Xcode",
    )

    data object FetchPackage : ModuleTaskTypes(
        internalName = "fetchPackage",
        operationMoniker = "fetching SwiftPM dependencies",
    )

    data object ComputeLocalPackageDependencies : ModuleTaskTypes(
        internalName = "computeLocalPackageDependencies",
        operationMoniker = "analyzing local SwiftPM dependencies",
    )

    // ***** Publish *****

    data object PrepareMavenPublishables : ModuleTaskTypes(
        internalName = "prepareMavenPublishables",
        operationMoniker = "preparing maven publishing",
    )

    data object PrepareMavenCentralBundle : ModuleTaskTypes(
        internalName = "prepareMavenCentralBundle",
        operationMoniker = "preparing the bundle for Maven Central",
    )

    class Publish(
        repositoryId: String,
    ) : ModuleTaskTypes(
        internalName = "publishTo${repositoryId.doCapitalize()}",
        operationMoniker = "publishing to `$repositoryId`",
    )

    data object AssembleAllMetadata: ModuleTaskTypes(
        internalName = "assembleMetadata",
        operationMoniker = "assembling all metadata",
    )
}