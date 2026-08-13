/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.frameworks.ApplePlatform
import com.jetbrains.cidr.xcode.frameworks.AppleProductType
import com.jetbrains.cidr.xcode.frameworks.AppleSdkManager
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import com.jetbrains.cidr.xcode.model.PBXBuildPhase
import com.jetbrains.cidr.xcode.model.PBXFileReference
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXProjectFileManipulator
import com.jetbrains.cidr.xcode.model.PBXReference
import com.jetbrains.cidr.xcode.model.PBXTarget
import com.jetbrains.cidr.xcode.model.ProjectFilesChanges
import com.jetbrains.cidr.xcode.model.addFileSystemSynchronizedRootGroup
import com.jetbrains.cidr.xcode.pbxproj.ObjectReference
import com.jetbrains.cidr.xcode.plist.Plist
import com.jetbrains.cidr.xcode.plist.XMLPlistDriver
import com.jetbrains.cidr.xcode.util.XcodeUserDataHolder
import fleet.com.intellij.openapi.util.UserDataHolderEx
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.singleSourceRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.ios.IosConventions.SCHEME_NAME
import org.jetbrains.amper.tasks.rootFragment
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Manages xcodeproj.
 *
 * * If the project is missing, generates a default conforming project & info.plist in the *source* directory.
 * * If the project exists, checks the Amper integration there and makes adjustments where possible;
 *   if not possible, errors are reported.
 */
class ManageXCodeProjectTask(
    override val taskName: TaskName,
    private val module: AmperModule,
) : Task {
    init {
        require(module.type == ProductType.IOS_APP) { "Wrong module type: ${module.type}" }
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        initializeXcodeComponentManager()
        val projectDir = module.xcodeProjectPath
        val baseDir = projectDir.parent
        val pbxProjectFilePath = projectDir / PBXProjectFile.PROJECT_FILE

        val target = if (pbxProjectFilePath.exists()) {
            logger.debug("Xcode project exists: {}", projectDir)

            spanBuilder("xcode project management")
                .setAmperModule(module)
                .use { span ->
                    validateAndUpdateProject(
                        projectDir = projectDir,
                        pbxProjectFilePath = pbxProjectFilePath,
                        span = span,
                    )
                }

        } else {
            logger.info("No Xcode project detected in '$projectDir', generating a buildable default")

            spanBuilder("xcode project generation")
                .setAmperModule(module)
                .use {
                    generateDefaultProject(
                        pbxProjectFilePath = pbxProjectFilePath,
                        baseDir = baseDir,
                    )
                }
        }

        ensureRequiredSchemePresent(projectDir, target)

        return Result(
            debugSettingsResolver = XcodeBuildSettingsResolution.FromModel.Resolver(target, BuildType.Debug),
            releaseSettingsResolver = XcodeBuildSettingsResolution.FromModel.Resolver(target, BuildType.Release),
        )
    }

    private fun validateAndUpdateProject(
        projectDir: Path,
        pbxProjectFilePath: Path,
        span: Span,
    ): PBXTarget {
        val pbxProjectFile: PBXProjectFile = PBXProjectFile(XcodeProjectHandle(), projectDir, pbxProjectFilePath)
            .apply {
                load(ProjectFilesChanges())
                lock()
            }

        val manipulator = pbxProjectFile.manipulator
        val [target, amperPhase] = manipulator.allTargets.mapNotNull { target ->
            target.buildPhases.find { phase ->
                phase.scriptText()?.let { scriptText ->
                    AMPER_PHASE_MAGIC_OLD in scriptText ||
                            AMPER_PHASE_MAGIC in scriptText
                } ?: false
            }?.let { amperPhase -> target to amperPhase }
        }.singleOrNull() ?: run {
            // TODO: Provide a way for the user to "fix" the corrupted project?
            userReadableError("XCodeProject '$projectDir' must contain a single target with the Kotlin Toolchain integration")
        }

        if (!isAmperPhaseValid(buildPhase = amperPhase)) {
            logger.warn("Kotlin Toolchain Phase is invalid, updating")
            managedAmperPhaseAttributes().forEach { (key, value) ->
                amperPhase[key] = value
            }
            // Need to truncate the file before saving,
            // as it doesn't overwrite the whole file and may leave some old text at the end of the file
            pbxProjectFile.pbxProjFile.writeText("")

            pbxProjectFile.save()
            span.setAttribute(UpdatedAttribute, true)
        } else {
            logger.debug("Kotlin Toolchain Phase is valid")
            span.setAttribute(UpdatedAttribute, false)
        }

        return target
    }

    private fun ensureRequiredSchemePresent(
        projectDir: Path,
        target: PBXTarget,
    ) {
        val scheme = projectDir / "xcshareddata/xcschemes/$SCHEME_NAME.xcscheme"
        if (scheme.isRegularFile()) {
            // For now we only check that the scheme exists with the required name.
            // TODO: Validate/adjust the scheme?
            logger.debug("Scheme is present at {}", scheme)
            return
        }
        logger.info("No shared scheme `$SCHEME_NAME` is found, generating a default one")

        val contents = generateDefaultBuildableSchemeContents(
            blueprintIdentifier = target.id.asString(),
            buildableName = (target.getAttribute("productReference", ObjectReference::class.java)
                ?.resolveObject() as PBXFileReference)
                .getNotNullString("path"),
            blueprintName = target.name,
            referencedContainer = IosConventions.XCODE_PROJECT_DIRECTORY_NAME,
        )
        scheme.createParentDirectories().writeText(contents)
    }

    private fun generateDefaultProject(
        pbxProjectFilePath: Path,
        baseDir: Path,
    ): PBXTarget {
        pbxProjectFilePath.createParentDirectories().createFile()

        val pbxProjectFile: PBXProjectFile = PBXProjectFileManipulator.createNewProject(
            project = XcodeProjectHandle(),
            baseDir = baseDir,
            pbxProjectFile = pbxProjectFilePath,
            initialId = null,
            projectName = null,
        )
        val manipulator: PBXProjectFileManipulator = pbxProjectFile.manipulator

        // Add configurations to the project level.
        BuildType.entries.forEach { buildType ->
            manipulator.addConfiguration(name = buildType.name, map = emptyMap(), target = null)
        }

        val src = module.rootFragment.singleSourceRoot("iOS applications must have a single source root")
        val infoPlistFile = src / "Info.plist"
        if (!infoPlistFile.exists()) {
            // Do not override the file if already exists
            logger.warn("The Info.plist already exists, no need to generate the default one.")
            XMLPlistDriver().write(createDefaultPlist(), infoPlistFile.toFile())
        }

        manipulator.addFile(
            path = infoPlistFile.pathString,
            targets = [],
            parentGroup = checkNotNull(pbxProjectFile.projectObject.mainGroup),
            isGroup = false,
        )

        val iosPlatform = checkNotNull(AppleSdkManager.getInstance().findPlatformByType(ApplePlatform.Type.IOS)) {
            "Unable to find iOS sdk"
        }

        val defaultProductName = module.userReadableName
        val defaultAppBundleId = inferDefaultAppBundleId()
        val baseSettings = buildMap {
            // Defaults:
            this[BuildSettingNames.INFOPLIST_FILE] = infoPlistFile.relativeTo(baseDir).pathString
            this[BuildSettingNames.PRODUCT_BUNDLE_IDENTIFIER] = defaultAppBundleId
            this[BuildSettingNames.SDKROOT] = iosPlatform.type.platformName  // iphoneos
            this[BuildSettingNames.PRODUCT_NAME] = defaultProductName
            this[BuildSettingNames.PRODUCT_MODULE_NAME] = PRODUCT_MODULE_NAME
            this[BuildSettingNames.TARGETED_DEVICE_FAMILY] = ["1", "2"]
            this[BuildSettingNames.ASSETCATALOG_COMPILER_APPICON_NAME] = "AppIcon"

            // Will not work if set to YES, validate at XCodeIntegrationCommand
            // Unless we set all inputs/outputs correctly (directories are not permitted), which is impossible.
            this["ENABLE_USER_SCRIPT_SANDBOXING"] = "NO"
            // NOTE: We deliberately do not customize `FRAMEWORK_SEARCH_PATHS` here:
            // the Kotlin framework is symlinked into `BUILT_PRODUCTS_DIR`, which Xcode already uses
            // as an implicit framework search path.
            // TODO: Move to the XConfig. For now generated a single time and is not managed anymore.
            this[KOTLIN_CLI_WRAPPER_PATH_CONF] = ProjectCliContext.wrapperScriptPath.relativeTo(baseDir).let {
                if (it.parent == null) Path(".") / it else it
            }.pathString

            // Misc defaults:
            this[BuildSettingNames.LD_RUNPATH_SEARCH_PATHS] =
                "$(inherited) @executable_path/Frameworks @loader_path/Frameworks"
            this[BuildSettingNames.SWIFT_VERSION] = "5.0"
            this[BuildSettingNames.ALWAYS_SEARCH_USER_PATHS] = "NO"
            this[BuildSettingNames.CLANG_ENABLE_MODULES] = "YES"
            this[BuildSettingNames.CLANG_ENABLE_OBJC_ARC] = "YES"
        }

        val pbxTarget = manipulator.addNativeTarget(
            name = DEFAULT_TARGET_NAME,
            productTypeId = AppleProductType.APPLICATION_TYPE_ID,
            // WARNING: This doesn't add all these settings to the target. They have to be added per configuration.
            buildSettings = baseSettings,
            platform = iosPlatform,
        )

        // Add "src" as a new PBXFileSystemSynchronizedRootGroup, xcode 16+
        manipulator.addFileSystemSynchronizedRootGroup(
            sourceTree = PBXReference.SOURCE_TREE_SOURCE_ROOT,
            name = "src",
            path = src.relativeTo(baseDir).pathString,
            addToTargets = [pbxTarget],
            membershipExceptions = [
                // Exclude Info.plist so it doesn't interfere with the build
                infoPlistFile.relativeTo(src).pathString
            ],
        )

        manipulator.addBuildPhase(
            PBXBuildPhase.Type.SHELL_SCRIPT,
            managedAmperPhaseAttributes(),
            pbxTarget,
        )

        manipulator.addBuildPhase(PBXBuildPhase.Type.SOURCES, emptyMap(), pbxTarget)
        manipulator.addBuildPhase(PBXBuildPhase.Type.FRAMEWORKS, emptyMap(), pbxTarget)
        manipulator.addBuildPhase(PBXBuildPhase.Type.RESOURCES, emptyMap(), pbxTarget)

        BuildType.entries.forEach { buildType ->
            val settings = baseSettings.toMutableMap().apply {
                when (buildType) {
                    BuildType.Debug -> {
                        this[BuildSettingNames.ONLY_ACTIVE_ARCH] = "YES"
                        this[BuildSettingNames.ENABLE_TESTABILITY] = "YES"
                        // Misc:
                        this[BuildSettingNames.SWIFT_OPTIMIZATION_LEVEL] = "-Onone"
                        this[BuildSettingNames.GCC_OPTIMIZATION_LEVEL] = "0"
                    }

                    BuildType.Release -> {
                        // Nothing here for now
                    }
                }
            }
            // Add configuration along with its settings to the target level
            manipulator.addConfiguration(buildType.name, settings, pbxTarget)
        }

        pbxProjectFile.save()

        return pbxTarget
    }

    private fun createDefaultPlist() = Plist().apply {
        // Base values
        this["CFBundleDevelopmentRegion"] = "$(DEVELOPMENT_LANGUAGE)"
        this["CFBundleExecutable"] = "$(EXECUTABLE_NAME)"
        this["CFBundleIdentifier"] = "$(PRODUCT_BUNDLE_IDENTIFIER)"
        this["CFBundleInfoDictionaryVersion"] = "6.0"
        this["CFBundleName"] = "$(PRODUCT_NAME)"
        this["CFBundlePackageType"] = "APPL"
        this["CFBundleShortVersionString"] = "1.0"
        this["CFBundleVersion"] = "1"

        // Specific values
        this["UILaunchScreen"] = mapOf<String, Any>().toPlist()

        // Needed for https://github.com/JetBrains/compose-multiplatform/issues/3634
        this["CADisableMinimumFrameDurationOnPhone"] = true
    }

    private fun inferDefaultAppBundleId(): String = listOfNotNull(
        module.rootFragment.settings.publishing.group?.takeIf { it.isNotBlank() },
        module.userReadableName.takeIf { it.isNotBlank() },
    ).joinToString(".")

    private fun isAmperPhaseValid(buildPhase: PBXBuildPhase): Boolean {
        val text = buildPhase.scriptText()
        if (text == null || AMPER_PHASE_MAGIC !in text) {
            return false
        }
        return managedAmperPhaseAttributes()
            .all { (key, value) ->
                val actualValue = buildPhase[key]
                if (actualValue != value) {
                    logger.warn("Mismatch in $key. Expected `$value`, got `$actualValue`")
                    false
                } else true
            }
    }

    private fun managedAmperPhaseAttributes(): Map<String?, Any> {
        return mapOf(
            "name" to "Build Kotlin",
            "shellPath" to "/bin/sh",
            "shellScript" to $$"""
                |# $$AMPER_PHASE_MAGIC
                |# This script is managed by the Kotlin Toolchain, do not edit manually!
                |"${$$KOTLIN_CLI_WRAPPER_PATH_CONF}" tool xcode-integration
                |
            """.trimMargin(),
            "alwaysOutOfDate" to "1", // TODO: Maybe track inputs/outputs properly if that's possible
        )
    }

    class Result(
        val debugSettingsResolver: XcodeBuildSettingsResolution.FromModel.Resolver,
        val releaseSettingsResolver: XcodeBuildSettingsResolution.FromModel.Resolver,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)

    private class XcodeProjectHandle : XcodeProjectId, UserDataHolderEx by XcodeUserDataHolder()

    companion object {
        private const val DEFAULT_TARGET_NAME = "app"
        private const val PRODUCT_MODULE_NAME = DEFAULT_TARGET_NAME

        private const val KOTLIN_CLI_WRAPPER_PATH_CONF = "KOTLIN_CLI_WRAPPER_PATH"

        private const val AMPER_PHASE_MAGIC_OLD = "!AMPER KMP INTEGRATION STEP!"
        private const val AMPER_PHASE_MAGIC = "!KOTLIN INTEGRATION STEP!"

        private val UpdatedAttribute = AttributeKey.booleanKey("updated")
    }
}
