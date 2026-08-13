/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import com.jetbrains.cidr.xcode.model.PBXTarget
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.util.BuildType

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
         * Reads the necessary settings from the [PBXTarget].
         */
        class Resolver(
            target: PBXTarget,
            private val buildType: BuildType,
        ) : XcodeBuildSettingsResolution.Resolver {
            private val resolverImpl = run {
                val configuration = target.buildConfigurations.find { it.name == buildType.name } ?: run {
                    // TODO: Assist user in creating this configuration back?
                    userReadableError("Missing ${buildType.name} configuration in Xcode project.")
                }
                ConfigurationSettingsResolver(target, configuration)
            }

            override fun getSetting(key: String): String? = resolverImpl.getBuildSetting(key).string

            override fun getRequiredSetting(key: String) = getSetting(key) ?: userReadableError(
                "Unable to resolve $key in the Xcode project. " +
                        "Please make sure the `$key` configuration option " +
                        "is properly set for configuration ${buildType.name}"
            )
        }
    }
}

val XcodeBuildSettingsResolution.Resolver.developmentTeam: String?
    get() = getSetting("DEVELOPMENT_TEAM")

val XcodeBuildSettingsResolution.Resolver.codeSigningAllowed: String?
    get() = getSetting("CODE_SIGNING_ALLOWED")

val XcodeBuildSettingsResolution.Resolver.productName: String
    get() = getRequiredSetting(BuildSettingNames.PRODUCT_NAME)

val XcodeBuildSettingsResolution.Resolver.productBundleIdentifier: String
    get() = getRequiredSetting(BuildSettingNames.PRODUCT_BUNDLE_IDENTIFIER)

