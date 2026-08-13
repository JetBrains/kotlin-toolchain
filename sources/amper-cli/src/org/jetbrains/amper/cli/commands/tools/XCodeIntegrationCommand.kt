/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands.tools

import kotlinx.serialization.json.Json
import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.cli.commands.AmperProjectAwareCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.project.preparePluginsAndReadModel
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.ios.IosConventions
import org.jetbrains.amper.tasks.ios.IosPreBuildTask
import org.jetbrains.amper.tasks.ios.ManageXCodeProjectTask
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div

internal class XCodeIntegrationCommand : AmperProjectAwareCommand(name = "xcode-integration") {

    private val env: Map<String, String> = System.getenv()

    override val hiddenFromHelp: Boolean
        get() = true

    override suspend fun run(cliContext: ProjectCliContext) {
        validateGeneralXcodeEnvironment()

        // Info passed down from the super Amper process if it's the case
        val readyPrebuildResult: IosPreBuildTask.Result? = env[IosPreBuildTask.Result.ENV_JSON_NAME]
            ?.let { envJson -> Json.decodeFromString(envJson) }

        val prebuildResult = readyPrebuildResult ?: run {
            // Running from xcode only - need to run iOS prebuild task ourselves
            withBackend(
                cliContext = cliContext,
                model = cliContext.preparePluginsAndReadModel(),
                // Resolve Xcode build settings from the environment instead of reading the model
                xcodeBuildSettingsResolution = EnvironmentXcodeBuildSettingsResolution(),
            ) { backend ->
                backend.prebuildForXcode(
                    moduleDir = Path(requireXcodeVar("PROJECT_DIR")),
                    buildType = inferBuildTypeFromEnv(),
                    platform = inferPlatformFromEnv(),
                )
            }
        }

        // Symlink the built framework, so the xcodebuild finds it during linking.
        linkFrameworkToConventionSearchLocation(prebuildResult, Path(requireXcodeVar("BUILT_PRODUCTS_DIR")))

        embedComposeResources(prebuildResult, Path(requireXcodeVar("TARGET_BUILD_DIR")))
    }

    private fun validateGeneralXcodeEnvironment() {
        if (env["ENABLE_USER_SCRIPT_SANDBOXING"] == "YES") {
            userReadableError(
                "Xcode option 'ENABLE_USER_SCRIPT_SANDBOXING' is enabled, which is unsupported. " +
                        "Please disable `User Script Sandboxing` option explicitly in Xcode."
            )
        }
    }

    /**
     * Symlinks the built framework into [xcodeBuiltProductsDir], which Xcode uses as an implicit framework search path,
     * so no `FRAMEWORK_SEARCH_PATHS` customization is needed in the Xcode project.
     */
    private fun linkFrameworkToConventionSearchLocation(
        prebuildResult: IosPreBuildTask.Result,
        xcodeBuiltProductsDir: Path,
    ) {
        val targetPath = xcodeBuiltProductsDir / prebuildResult.appFrameworkPath.fileName
        targetPath.createParentDirectories()
        targetPath.deleteIfExists()
        targetPath.createSymbolicLinkPointingTo(prebuildResult.appFrameworkPath)
    }

    private suspend fun embedComposeResources(
        prebuildResult: IosPreBuildTask.Result,
        xcodeTargetBuildDir: Path,
    ) {
        val embeddedComposeResourcesDir = xcodeTargetBuildDir / requireXcodeVar("CONTENTS_FOLDER_PATH") /
                IosConventions.COMPOSE_RESOURCES_CONTENT_DIR_NAME
        embeddedComposeResourcesDir.apply {
            createParentDirectories()
            deleteRecursively()
        }
        prebuildResult.composeResourcesDirectoryPath?.let { path ->
            BuildPrimitives.copy(
                from = path,
                to = embeddedComposeResourcesDir,
                followLinks = true,
            )
        }
    }

    private fun inferBuildTypeFromEnv(): BuildType = requireXcodeVar("CONFIGURATION").let { value ->
        BuildType.entries.find { it.name == value } ?: userReadableError("Invalid `CONFIGURATION`: `$value`")
    }

    private fun inferPlatformFromEnv(): Platform {
        val sdk: String = requireXcodeVar("PLATFORM_NAME")
        return requireXcodeVar("ARCHS").split(' ').let { archs ->
            archs.singleOrNull()
                ?: userReadableError("Building multiple architectures in a single call is unsupported for now: $archs")
        }.let { value ->
            when ("$value:$sdk") {
                "arm64:$IPHONE_SDK_NAME" -> Platform.IOS_ARM64
                "arm64:$SIMULATOR_SDK_NAME" -> Platform.IOS_SIMULATOR_ARM64
                "x86_64:$SIMULATOR_SDK_NAME" -> Platform.IOS_X64
                else -> userReadableError("The Kotlin Toolchain currently doesn't support building the following configuration: " +
                        "arch=$value, sdk=$sdk")
            }
        }
    }

    private fun requireXcodeVar(name: String): String {
        return env[name] ?: userReadableError(
            "Invalid environment: missing xcode variable `$name`"
        )
    }

    private inner class EnvironmentXcodeBuildSettingsResolution
        : XcodeBuildSettingsResolution, XcodeBuildSettingsResolution.Resolver {
        override fun taskDependency(module: AmperModule): Nothing? = null
        override fun getRequiredSetting(key: String) = requireXcodeVar(key)
        override fun getSetting(key: String) = env[key]

        override fun getResolver(
            buildType: BuildType,
            dependencyResult: List<TaskResult>,
        ): XcodeBuildSettingsResolution.Resolver {
            check(dependencyResult.filterIsInstance<ManageXCodeProjectTask.Result>().isEmpty()) {
                "Dependency on `ManageXCodeProjectTask` is detected, but not permitted: " +
                        "we are already running under Xcode."
            }
            val activeBuildType = inferBuildTypeFromEnv()
            check(activeBuildType == buildType) {
                "Xcode launched us to build $activeBuildType, settings for $buildType are not available"
            }
            // We only support resolution for the active build type.
            return this
        }
    }
}

private const val SIMULATOR_SDK_NAME = "iphonesimulator"
private const val IPHONE_SDK_NAME = "iphoneos"
