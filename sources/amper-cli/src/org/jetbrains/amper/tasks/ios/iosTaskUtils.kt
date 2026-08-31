/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.apple.sdk.ArchitectureValue
import com.jetbrains.cidr.xcode.frameworks.AppleSdk
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingsResolver
import com.jetbrains.cidr.xcode.model.PBXBuildPhase
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXTarget
import com.jetbrains.cidr.xcode.model.XCBuildConfiguration
import com.jetbrains.cidr.xcode.plist.Plist
import org.jetbrains.amper.frontend.Platform

internal val Platform.isIosSimulator
    get() = when(this) {
        Platform.IOS_X64, Platform.IOS_SIMULATOR_ARM64 -> true
        else -> false
    }

internal fun Map<String, *>.toPlist(): Plist = Plist().also { plist ->
    for ([k, v] in this) {
        @Suppress("UNCHECKED_CAST") // non-string keys will fail in toPlist() when used as Strings
        plist[k] = when (v) {
            is Map<*, *> -> (v as Map<String, *>).toPlist()
            else -> v
        }
    }
}

internal fun PBXBuildPhase.scriptText(): String? {
    if (type != PBXBuildPhase.Type.SHELL_SCRIPT) {
        return null
    }
    return (this["shellScript"] as? String).orEmpty()
}

internal class ConfigurationSettingsResolver(
    override val target: PBXTarget,
    override val buildConfiguration: XCBuildConfiguration,
) : BuildSettingsResolver() {
    override val projectFile: PBXProjectFile get() = target.file
    override val architectures: Set<ArchitectureValue>? get() = null
    override val sdk: AppleSdk? get() = null
    override fun areSdkAndArchitectureOverridden() = true
}

internal fun generateDefaultBuildableSchemeContents(
    blueprintIdentifier: String,
    buildableName: String,
    blueprintName: String,
    referencedContainer: String,
): String = """
    |<?xml version="1.0" encoding="UTF-8"?>
    |<Scheme
    |   LastUpgradeVersion = "1640"
    |   version = "1.7">
    |   <BuildAction
    |      parallelizeBuildables = "YES"
    |      buildImplicitDependencies = "YES"
    |      buildArchitectures = "Automatic">
    |      <BuildActionEntries>
    |         <BuildActionEntry
    |            buildForTesting = "YES"
    |            buildForRunning = "YES"
    |            buildForProfiling = "YES"
    |            buildForArchiving = "YES"
    |            buildForAnalyzing = "YES">
    |            <BuildableReference
    |               BuildableIdentifier = "primary"
    |               BlueprintIdentifier = "$blueprintIdentifier"
    |               BuildableName = "$buildableName"
    |               BlueprintName = "$blueprintName"
    |               ReferencedContainer = "container:$referencedContainer">
    |            </BuildableReference>
    |         </BuildActionEntry>
    |      </BuildActionEntries>
    |   </BuildAction>
    |   <TestAction
    |      buildConfiguration = "Debug"
    |      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
    |      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
    |      shouldUseLaunchSchemeArgsEnv = "YES"
    |      shouldAutocreateTestPlan = "YES">
    |      <Testables>
    |      </Testables>
    |   </TestAction>
    |   <LaunchAction
    |      buildConfiguration = "Debug"
    |      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
    |      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
    |      launchStyle = "0"
    |      useCustomWorkingDirectory = "NO"
    |      ignoresPersistentStateOnLaunch = "NO"
    |      debugDocumentVersioning = "YES"
    |      debugServiceExtension = "internal"
    |      allowLocationSimulation = "YES">
    |      <BuildableProductRunnable
    |         runnableDebuggingMode = "0">
    |         <BuildableReference
    |            BuildableIdentifier = "primary"
    |            BlueprintIdentifier = "$blueprintIdentifier"
    |            BuildableName = "$buildableName"
    |            BlueprintName = "$blueprintName"
    |            ReferencedContainer = "container:$referencedContainer">
    |         </BuildableReference>
    |      </BuildableProductRunnable>
    |   </LaunchAction>
    |   <ProfileAction
    |      buildConfiguration = "Release"
    |      shouldUseLaunchSchemeArgsEnv = "YES"
    |      savedToolIdentifier = ""
    |      useCustomWorkingDirectory = "NO"
    |      debugDocumentVersioning = "YES">
    |      <BuildableProductRunnable
    |         runnableDebuggingMode = "0">
    |         <BuildableReference
    |            BuildableIdentifier = "primary"
    |            BlueprintIdentifier = "$blueprintIdentifier"
    |            BuildableName = "$buildableName"
    |            BlueprintName = "$blueprintName"
    |            ReferencedContainer = "container:$referencedContainer">
    |         </BuildableReference>
    |      </BuildableProductRunnable>
    |   </ProfileAction>
    |   <AnalyzeAction
    |      buildConfiguration = "Debug">
    |   </AnalyzeAction>
    |   <ArchiveAction
    |      buildConfiguration = "Release"
    |      revealArchiveInOrganizer = "YES">
    |   </ArchiveAction>
    |</Scheme>
""".trimMargin() + "\n"
