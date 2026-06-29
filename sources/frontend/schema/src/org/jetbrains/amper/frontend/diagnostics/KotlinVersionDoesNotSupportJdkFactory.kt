/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.asBuildProblemSource
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
 * Well-known mapping of Kotlin compiler versions to the maximum JDK (JVM bytecode target) they support.
 *
 * Each rule states that starting with [minKotlinVersion], the compiler can target up to (and including) JDK [maxJdk].
 * Add new entries here as new Kotlin versions extend JDK support.
 */
private val KotlinJdkSupportRules: List<KotlinJdkSupportRule> = listOf(
    KotlinJdkSupportRule(
        minKotlinVersion = "2.1.0",
        maxJdk = 23
    ), // https://kotlinlang.org/docs/whatsnew21.html#kotlin-jvm
    KotlinJdkSupportRule(
        minKotlinVersion = "2.2.0",
        maxJdk = 24
    ), // https://kotlinlang.org/docs/whatsnew22.html#kotlin-jvm
    KotlinJdkSupportRule(
        minKotlinVersion = "2.3.0",
        maxJdk = 25
    ), // https://kotlinlang.org/docs/whatsnew23.html#kotlin-jvm-support-for-java-25
    KotlinJdkSupportRule(
        minKotlinVersion = "2.4.0",
        maxJdk = 26
    ), // https://kotlinlang.org/docs/whatsnew24.html#support-for-java-26
    KotlinJdkSupportRule(
        minKotlinVersion = "2.5.0",
        maxJdk = null
    ), // this is a stub for the last "range".
)

private data class KotlinJdkSupportRule(val minKotlinVersion: ComparableVersion, val maxJdk: Int?) {
    constructor(minKotlinVersion: String, maxJdk: Int?) : this(ComparableVersion(minKotlinVersion), maxJdk)
}

/**
 * Detects unsupported combinations of the Kotlin compiler version and upper JDK version, based on the well-known
 * incompatibilities in [KotlinJdkSupportRules].
 * 
 * Note: Minimum compatible JDK version check is intentionally omitted from this diagnostic,
 * sine all kotlin versions since 1.5 support JDK 8.
 */
object KotlinVersionDoesNotSupportJdkFactory : AomSingleModuleDiagnosticFactory {

    override fun analyze(module: AmperModule, problemReporter: ProblemReporter) {
        val reportedPlaces = mutableSetOf<Pair<Trace, Trace>>()
        module.fragments.forEach { fragment ->
            val settings = fragment.settings
            val kotlinVersion = ComparableVersion(settings.kotlin.version)
            val jdkVersion = settings.jvm.jdk.version

            // We should know something about Kotlin version used, or just drop this diagnostic.
            val maxJdk = maxSupportedJdkFor(kotlinVersion) ?: return@forEach

            // We are fine if used verion is lower/equals than max supported one.
            if (jdkVersion <= maxJdk) return@forEach

            val alreadyReported = !reportedPlaces.add(
                settings.kotlin.versionDelegate.trace to settings.jvm.jdk.versionDelegate.trace
            )
            if (!alreadyReported) {
                problemReporter.reportMessage(
                    KotlinVersionDoesNotSupportJdk(
                        actualKotlinVersion = TraceableString(
                            value = settings.kotlin.version,
                            trace = settings.kotlin.versionDelegate.trace,
                        ),
                        actualJdkVersion = TraceableValue(
                            value = jdkVersion,
                            trace = settings.jvm.jdk.versionDelegate.trace,
                        ),
                        maxSupportedJdk = maxJdk,
                        minKotlinVersionForJdk = minKotlinVersionFor(jdkVersion),
                    )
                )
            }
        }
    }

    private fun maxSupportedJdkFor(kotlinVersion: ComparableVersion): Int? {
        for ((index, value) in KotlinJdkSupportRules.withIndex()) {
            val lowerBound = value.minKotlinVersion
            val upperBound = KotlinJdkSupportRules.getOrNull(index + 1)?.minKotlinVersion ?: continue
            if (kotlinVersion in lowerBound..<upperBound) return value.maxJdk
        }
        return null
    }

    private fun minKotlinVersionFor(jdkVersion: Int): String? =
        KotlinJdkSupportRules
            .filter { it.maxJdk != null && it.maxJdk >= jdkVersion }
            .minByOrNull { it.minKotlinVersion }
            ?.minKotlinVersion
            ?.toString()
}

class KotlinVersionDoesNotSupportJdk(
    val actualKotlinVersion: TraceableString,
    val actualJdkVersion: TraceableValue<Int>,
    val maxSupportedJdk: Int,
    val minKotlinVersionForJdk: String?,
) : BuildProblem {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.KotlinVersionDoesNotSupportJdk
    override val message = if (minKotlinVersionForJdk != null)
        SchemaBundle.message(
            messageKey = "kotlin.version.does.not.support.jdk",
            actualKotlinVersion.value, maxSupportedJdk, actualJdkVersion.value, minKotlinVersionForJdk,
        ) else SchemaBundle.message(
        messageKey = "kotlin.version.does.not.support.jdk.unknown.jdk.version",
        actualKotlinVersion.value, maxSupportedJdk, actualJdkVersion.value,
    )
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
    override val source: BuildProblemSource = MultipleLocationsBuildProblemSource(
        sources = listOf(
            actualKotlinVersion.asBuildProblemSource(),
            actualJdkVersion.asBuildProblemSource(),
        ).filterIsInstance<FileBuildProblemSource>(),
        groupingMessage = message,
    )
}