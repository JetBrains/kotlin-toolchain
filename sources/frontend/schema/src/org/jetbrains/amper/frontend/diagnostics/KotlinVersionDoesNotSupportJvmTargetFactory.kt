/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.api.asTraceableValue
import org.jetbrains.amper.frontend.api.isExplicitlySet
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.jvmSettings
import org.jetbrains.amper.frontend.schema.JvmSettings
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
import org.jetbrains.amper.stdlib.collections.zipWithNextOrNull

/**
 * All known bumps of the maximum supported JVM bytecode target, and in which Kotlin compiler version they occurred.
 *
 * Each rule states that starting with [kotlinVersion][MaxJvmTargetBump.kotlinVersion], the compiler can
 * target up to (and including) the JVM bytecode level defined by [newMaxJvmTarget][MaxJvmTargetBump.newMaxJvmTarget].
 * Add new entries here as new Kotlin versions extend JDK support.
 */
private val MaxJvmTargetBumps: List<MaxJvmTargetBump> = listOf(
    MaxJvmTargetBump(newMaxJvmTarget = 23, KotlinVersion("2.1.0-Beta2")), // KT-71537
    MaxJvmTargetBump(newMaxJvmTarget = 24, KotlinVersion("2.2.0-Beta2")), // KT-76095
    MaxJvmTargetBump(newMaxJvmTarget = 25, KotlinVersion("2.3.0-Beta2")), // KT-81077
    MaxJvmTargetBump(newMaxJvmTarget = 26, KotlinVersion("2.4.0-Beta1")), // KT-84319
    MaxJvmTargetBump(newMaxJvmTarget = 27, KotlinVersion("2.5.0-Beta1")), // KT-87767
    MaxJvmTargetBump(newMaxJvmTarget = 28, KotlinVersion("2.5.0-Beta2")), // KT-89646
)

/**
 * The latest known stable Kotlin version, to decide whether we should mention beta versions in error messages.
 *
 * Example: the user sets release 25 with a too old Kotlin version. Even though the max JVM target 25 was introduced in
 * Kotlin `2.3.0-Beta2`, we don't advise users to bump to that version, and instead offer to bump to `2.3.0`.
 */
private val LatestKnownStableKotlinVersion = KotlinVersion("2.4.21")

private data class MaxJvmTargetBump(
    /**
     * The new maximum JVM target bytecode introduced in [kotlinVersion].
     */
    val newMaxJvmTarget: Int,
    /**
     * The first Kotlin version that supports the [newMaxJvmTarget].
     */
    val kotlinVersion: KotlinVersion,
)

/**
 * Non-overlapping ranges of Kotlin versions with the same known maximum JVM target bytecode.
 */
private val MaxJvmTargetRanges = MaxJvmTargetBumps
    .sortedBy { it.kotlinVersion }
    .zipWithNextOrNull()
    .map { [current, next] ->
        MaxJvmTargetRange(
            start = current.kotlinVersion,
            end = next?.kotlinVersion ?: current.kotlinVersion,
            endIsExclusive = next != null,
            maxJvmTarget = current.newMaxJvmTarget,
        )
    }

/**
 * A range of Kotlin versions with the same known maximum JVM target bytecode.
 */
private data class MaxJvmTargetRange(
    val start: KotlinVersion,
    val end: KotlinVersion,
    val endIsExclusive: Boolean,
    val maxJvmTarget: Int,
) {
    operator fun contains(version: KotlinVersion): Boolean = if (endIsExclusive) {
        version in start..<end
    } else {
        version in start..end
    }
}

/**
 * Detects unsupported combinations of Kotlin compiler version and JVM target version.
 *
 * Note: Minimum compatible JDK version check is intentionally omitted from this diagnostic,
 * sine all kotlin versions since 1.5 support JDK 8.
 */
object KotlinVersionDoesNotSupportJvmTargetFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        // We don't have a module-level helper for Kotlin settings because not all subsettings are platform-agnostic
        // It only works here because we only look at the version setting
        val kotlinSettings = module.fragments.firstOrNull { !it.isTest }?.settings?.kotlin ?: return
        val kotlinVersion = kotlinSettings.version
        val kotlinVersionDelegate = kotlinSettings.versionDelegate

        val jvmSettings = module.jvmSettings

        // A null release means the user wants the Kotlin compiler's own default JVM target, which doesn't depend
        // on the JDK and is always supported, so nothing to check.
        val currentJvmTarget = jvmSettings.release ?: return

        // We return early if we don't have information about the maximum JVM target of the Kotlin version
        val maxJvmTarget = maxSupportedTargetFor(kotlinVersion) ?: return
        if (currentJvmTarget <= maxJvmTarget) {
            return
        }
        problemReporter.reportMessage(
            KotlinVersionDoesNotSupportJvmTarget(
                actualKotlinVersion = kotlinVersionDelegate.asTraceableValue(),
                actualJvmReleaseVersion = TraceableValue(
                    value = currentJvmTarget,
                    // When the release is not set explicitly, this trace points at the JDK version that it
                    // references, which is exactly the place the user has to change.
                    trace = jvmSettings.releaseDelegate.trace,
                ),
                maxSupportedJvmTarget = maxJvmTarget,
                minKotlinVersionForJvmTarget = minKotlinVersionFor(currentJvmTarget),
                jvmTargetOrigin = jvmSettings.jvmTargetOrigin,
            )
        )
    }

    private fun maxSupportedTargetFor(kotlinVersion: KotlinVersion): Int? = MaxJvmTargetRanges
        .firstOrNull { kotlinVersion in it }
        ?.maxJvmTarget

    private fun minKotlinVersionFor(jvmTarget: Int): String? =
        MaxJvmTargetBumps
            .filter { it.newMaxJvmTarget >= jvmTarget }
            .minOfOrNull { it.kotlinVersion }
            ?.toStable()
            ?.notation

    private fun KotlinVersion.toStable(): KotlinVersion {
        if (this >= LatestKnownStableKotlinVersion) {
            return this
        }
        val match = Regex("""(?<stable>\d+\.\d+\.\d+)(-.*)?""").matchEntire(notation) ?: return this
        val stableNotation = match.groups["stable"]?.value ?: error("Regex matched by 'stable' group is absent")
        return KotlinVersion(stableNotation)
    }
}

class KotlinVersionDoesNotSupportJvmTarget(
    val actualKotlinVersion: TraceableValue<KotlinVersion>,
    val actualJvmReleaseVersion: TraceableValue<Int>,
    val maxSupportedJvmTarget: Int,
    val minKotlinVersionForJvmTarget: String?,
    val jvmTargetOrigin: JvmTargetOrigin,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.KotlinVersionDoesNotSupportJvmTarget
    override val message = if (minKotlinVersionForJvmTarget != null) {
        SchemaBundle.message(
            messageKey = when (jvmTargetOrigin) {
                JvmTargetOrigin.Release -> "kotlin.version.does.not.support.jvm.target.via.release"
                JvmTargetOrigin.Jdk -> "kotlin.version.does.not.support.jvm.target.via.jdk"
                JvmTargetOrigin.Defaults -> "kotlin.version.does.not.support.jvm.target.via.defaults"
            },
            actualKotlinVersion.value.notation,
            maxSupportedJvmTarget,
            actualJvmReleaseVersion.value,
            minKotlinVersionForJvmTarget,
        )
    } else {
        SchemaBundle.message(
            messageKey = when (jvmTargetOrigin) {
                JvmTargetOrigin.Release ->
                    "kotlin.version.does.not.support.jvm.target.via.release.unknown.min.kotlin"
                JvmTargetOrigin.Jdk ->
                    "kotlin.version.does.not.support.jvm.target.via.jdk.unknown.min.kotlin"
                JvmTargetOrigin.Defaults ->
                    "kotlin.version.does.not.support.jvm.target.via.defaults.unknown.min.kotlin"
            },
            actualKotlinVersion.value.notation, maxSupportedJvmTarget, actualJvmReleaseVersion.value,
        )
    }
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = MultipleLocationsBuildProblemSource(
        sources = listOf(
            actualKotlinVersion.asBuildProblemSource(),
            actualJvmReleaseVersion.asBuildProblemSource(),
        ).filterIsInstance<FileBuildProblemSource>(),
        groupingMessage = message,
    )
}

/**
 * The origin of the JVM target version that will be used to compile, which dictates how users can fix problems.
 */
enum class JvmTargetOrigin {
    /**
     * `settings.jvm.release` is explicitly set to a version.
     */
    Release,

    /**
     * `settings.jvm.jdk.version` is explicitly set, and `settings.jvm.release` defaults to it.
     */
    Jdk,

    /**
     * Nothing relevant is explicitly set, so the target comes from the default JDK version.
     */
    Defaults,
}

private val JvmSettings.jvmTargetOrigin: JvmTargetOrigin
    get() = when {
        releaseDelegate.isExplicitlySet -> JvmTargetOrigin.Release
        jdk.versionDelegate.isExplicitlySet -> JvmTargetOrigin.Jdk
        else -> JvmTargetOrigin.Defaults
    }
