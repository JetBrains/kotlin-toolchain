/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXTarget
import com.jetbrains.cidr.xcode.model.ProjectFilesChanges
import com.jetbrains.cidr.xcode.model.XCBuildConfiguration
import com.jetbrains.cidr.xcode.model.saveProperly
import com.jetbrains.cidr.xcode.util.XcodeUserDataHolder
import fleet.com.intellij.openapi.util.UserDataHolderEx
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.util.BuildType
import kotlin.io.path.div
import kotlin.io.path.isRegularFile

/**
 * A strategy to resolve Xcode project build settings.
 */
interface XcodeBuildSettingsResolution {
    /**
     * Every task that wants to call [getResolver] and query build settings needs to have this dependency added for it.
     * Returns `null` if no additional dependency is necessary.
     */
    fun taskDependency(module: AmperModule): TaskName?

    /**
     * Returns a [Resolver] implemenation for the given [buildType]
     * using [dependencyResult] to extract the necessary info if needed.
     */
    fun getResolver(buildType: BuildType, dependencyResult: List<TaskResult>): Resolver

    /**
     * Returns Xcode build settings
     */
    interface Resolver {
        /**
         * Returns the setting's value or `null` if no such setting is set.
         */
        fun getSetting(key: String): String?

        /**
         * Returns the setting's value or throws [org.jetbrains.amper.cli.UserReadableError] if no such setting is set.
         */
        fun getRequiredSetting(key: String): String
    }

    /**
     * Default implementation that relies on [ManageXCodeProjectTask] for reading the model of the project.
     */
    companion object FromModel : XcodeBuildSettingsResolution {
        override fun taskDependency(module: AmperModule) = ModuleTaskTypes.ManageXCodeProject.getTaskName(module)

        override fun getResolver(
            buildType: BuildType,
            dependencyResult: List<TaskResult>,
        ): XcodeBuildSettingsResolution.Resolver {
            val result = dependencyResult.filterIsInstance<ManageXCodeProjectTask.Result>()
                .singleOrNull() ?: error("the task doesn't depend on the `ManageXCodeProjectTask`. " +
                    "Use XcodeBuildSettingsResolution.taskDependency() to setup proper task dependency")
            return when (buildType) {
                BuildType.Debug -> result.debugSettingsResolver
                BuildType.Release -> result.releaseSettingsResolver
            }
        }

        /**
         * Loads an existing module project without running tasks, generating files, or updating its integration.
         * The supplied environment must have been initialized by [getXcodeEnvironment].
         */
        context(@Suppress("unused") _: XcodeEnvironment)
        internal fun load(module: AmperModule, buildType: BuildType): Resolver {
            val projectDir = module.xcodeProjectPath
            val pbxProjectFilePath = projectDir / PBXProjectFile.PROJECT_FILE
            if (!pbxProjectFilePath.isRegularFile()) {
                userReadableError(
                    "No Xcode project found at '$projectDir'. " +
                            "Restore the module's Xcode project from version control or add an existing project " +
                            "with the Kotlin Toolchain integration at this location, then retry."
                )
            }
            val projectFile = PBXProjectFile(XcodeProjectHandle(), projectDir, pbxProjectFilePath).apply {
                load(ProjectFilesChanges())
                lock()
            }
            val target = ManageXCodeProjectTask.findKotlinIntegratedTarget(projectFile)?.first
                ?: userReadableError(
                    "Xcode project '$projectDir' must contain a single target with the Kotlin Toolchain integration"
                )
            return Resolver(target, buildType)
        }

        private class XcodeProjectHandle : XcodeProjectId, UserDataHolderEx by XcodeUserDataHolder()

        /**
         * Reads the necessary settings from the [PBXTarget].
         */
        class Resolver(
            private val target: PBXTarget,
            private val buildType: BuildType,
        ) : XcodeBuildSettingsResolution.Resolver {
            private val configuration = target.buildConfigurations.find { it.name == buildType.name } ?: run {
                // TODO: Assist user in creating this configuration back?
                userReadableError("Missing ${buildType.name} configuration in Xcode project.")
            }
            private val resolverImpl = ConfigurationSettingsResolver(target, configuration)
            private val buildSettings
                get() = configuration.getDictionary(XCBuildConfiguration.BUILD_SETTINGS_ATTRIBUTE, null)
                    ?: userReadableError("The ${buildType.name} configuration in the Xcode project has no build settings")

            override fun getSetting(key: String): String? = resolverImpl.getBuildSetting(key).string

            override fun getRequiredSetting(key: String) = getSetting(key) ?: userReadableError(
                "Unable to resolve $key in the Xcode project. " +
                        "Please make sure the `$key` configuration option " +
                        "is properly set for configuration ${buildType.name}"
            )

            /** Writes a target configuration setting and immediately saves the project. */
            internal fun setSetting(key: String, value: String) {
                buildSettings.setAttribute(key, value)
                target.file.saveProperly()
            }

            internal fun hasQualifiedSetting(key: String): Boolean = buildSettings.keys.any { it.startsWith("$key[") }

            /** Replaces settings and all their qualified variants, then saves the project once. */
            internal fun setSettingsWithoutQualifiers(settings: Map<String, String>) {
                val buildSettings = buildSettings
                val keysToRemove = buildSettings.keys.filter { existingKey ->
                    settings.keys.any { key -> existingKey == key || existingKey.startsWith("$key[") }
                }
                for (key in keysToRemove) {
                    buildSettings.remove(key)
                }
                for ([key, value] in settings) {
                    buildSettings.setAttribute(key, value)
                }
                target.file.saveProperly()
            }
        }
    }
}

val XcodeBuildSettingsResolution.Resolver.developmentTeam: String?
    get() = getSetting("DEVELOPMENT_TEAM")

val XcodeBuildSettingsResolution.Resolver.codeSigningAllowed: String?
    get() = getSetting("CODE_SIGNING_ALLOWED")

/**
 * The value of `CODE_SIGN_STYLE`: `Automatic` (the default) or `Manual`.
 */
val XcodeBuildSettingsResolution.Resolver.codeSignStyle: String?
    get() = getSetting("CODE_SIGN_STYLE")

val XcodeBuildSettingsResolution.Resolver.productName: String
    get() = getRequiredSetting(BuildSettingNames.PRODUCT_NAME)

val XcodeBuildSettingsResolution.Resolver.productBundleIdentifier: String
    get() = getRequiredSetting(BuildSettingNames.PRODUCT_BUNDLE_IDENTIFIER)

val XcodeBuildSettingsResolution.Resolver.iphoneOsDeploymentTarget: String?
    get() = getSetting("IPHONEOS_DEPLOYMENT_TARGET")

val XcodeBuildSettingsResolution.Resolver.infoPlistPath: String?
    get() = getSetting("INFOPLIST_FILE")