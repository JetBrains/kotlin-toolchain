/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.api.asTraceableValue
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.helpers.toStable
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * The Apple platforms that are deprecated in Kotlin, with the corresponding Kotlin versions.
 */
private val ApplePlatformsSupportEnd = mapOf(
    Platform.MACOS_X64 to PlatformSupportEnd(
        warningSince = KotlinVersion("2.3.20-Beta1"), // KT-81596
        errorSince = KotlinVersion("2.5.0-Beta1"), // KT-84955
    ),
    Platform.TVOS_X64 to PlatformSupportEnd(
        warningSince = KotlinVersion("2.3.20-Beta1"), // KT-81596
        errorSince = KotlinVersion("2.5.0-Beta1"), // KT-84955
    ),
    Platform.WATCHOS_ARM32 to PlatformSupportEnd(
        warningSince = KotlinVersion("2.4.20-Beta2"), // KT-87512
        errorSince = KotlinVersion("2.5.0-Beta2"), // KT-87513
    ),
)

private data class PlatformSupportEnd(
    /**
     * The first Kotlin version in which the platform is deprecated (at warning level).
     */
    val warningSince: KotlinVersion,
    /**
     * The first Kotlin version in which the platform is no longer supported and the compiler emits an error.
     */
    val errorSince: KotlinVersion,
)

/**
 * Reports the usage of Apple platforms with Kotlin versions that deprecated or removed them.
 */
object ApplePlatformsKotlinSupportFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val endingPlatforms = module.commonModuleNode.product.platforms.filter { it.value in ApplePlatformsSupportEnd }
        if (endingPlatforms.isEmpty()) {
            return
        }
        // The Kotlin version is platform-agnostic, so any production fragment gives the module's version
        val kotlinSettings = module.fragments.firstOrNull { !it.isTest }?.settings?.kotlin ?: return
        val kotlinVersion = kotlinSettings.versionDelegate.asTraceableValue()
        endingPlatforms
            .mapNotNull { supportProblemOrNull(it, kotlinVersion) }
            .forEach { problemReporter.reportMessage(it) }
    }

    private fun supportProblemOrNull(
        platform: TraceableValue<Platform>,
        kotlinVersion: TraceableValue<KotlinVersion>,
    ): BuildProblem? {
        val (warningSince, errorSince) = ApplePlatformsSupportEnd.getValue(platform.value)
        return when {
            kotlinVersion.value >= errorSince -> ApplePlatformNotSupportedByKotlin(
                actualKotlinVersion = kotlinVersion,
                platform = platform,
                deprecatedSince = warningSince.toStable(),
                errorSince = errorSince.toStable(),
            )
            kotlinVersion.value >= warningSince -> ApplePlatformDeprecatedInKotlin(
                actualKotlinVersion = kotlinVersion,
                platform = platform,
                deprecatedSince = warningSince.toStable(),
            )
            else -> null
        }
    }
}

sealed class ApplePlatformKotlinSupportProblem(
    val actualKotlinVersion: TraceableValue<KotlinVersion>,
    val platform: TraceableValue<Platform>,
    val deprecatedSince: KotlinVersion,
) : BuildProblem {
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource
        get() = MultipleLocationsBuildProblemSource(
            sources = listOf(
                actualKotlinVersion.asBuildProblemSource(),
                platform.asBuildProblemSource(),
            ).filterIsInstance<FileBuildProblemSource>(),
            groupingMessage = message,
        )
}

class ApplePlatformDeprecatedInKotlin(
    actualKotlinVersion: TraceableValue<KotlinVersion>,
    platform: TraceableValue<Platform>,
    deprecatedSince: KotlinVersion,
) : ApplePlatformKotlinSupportProblem(actualKotlinVersion, platform, deprecatedSince) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ApplePlatformDeprecatedInKotlin
    override val message = SchemaBundle.message(
        "apple.platform.deprecated.in.kotlin",
        platform.value.schemaValue,
        deprecatedSince.notation,
        actualKotlinVersion.value.notation,
    )
    override val level: Level = Level.Warning
}

class ApplePlatformNotSupportedByKotlin(
    actualKotlinVersion: TraceableValue<KotlinVersion>,
    platform: TraceableValue<Platform>,
    deprecatedSince: KotlinVersion,
    val errorSince: KotlinVersion,
) : ApplePlatformKotlinSupportProblem(actualKotlinVersion, platform, deprecatedSince) {
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.ApplePlatformNotSupportedByKotlin
    override val message = SchemaBundle.message(
        "apple.platform.not.supported.by.kotlin",
        platform.value.schemaValue,
        errorSince.notation,
        actualKotlinVersion.value.notation,
    )
    override val level: Level = Level.Error
}
