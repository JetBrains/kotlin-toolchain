/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.context

import com.github.ajalt.mordant.terminal.Terminal
import io.opentelemetry.api.OpenTelemetry
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.problems.reporting.ProblemReporter

sealed interface CliContext {
    /**
     * The name of the currently running command.
     */
    val commandName: String

    /**
     * The root of the caches shared between projects, usually local to the machine.
     */
    val userCacheRoot: AmperUserCacheRoot

    /**
     * The Mordant [Terminal] to use to interact with the user.
     */
    val terminal: Terminal

    /**
     * A centralized way to report diagnostics and issues.
     */
    val problemReporter: ProblemReporter

    /**
     * The [ProcessRunner] to use to run any child process. It ensures we don't leak processes, and integrates them with
     * structured concurrency.
     */
    val processRunner: ProcessRunner

    /**
     * The centralized incremental cache mechanism.
     */
    val incrementalCache: IncrementalCache

    val openTelemetry: OpenTelemetry

    /**
     * The detected Android SDK home root to use for Android tools.
     */
    val androidHomeRoot: AndroidHomeRoot

    /**
     * A service that provisions Android SDK packages on-demand. A single instance is used for the whole Kotlin
     * Toolchain execution so parsed Android SDK repositories can be reused between tasks.
     */
    val androidSdkProvider: AndroidSdkProvider

    /**
     * A service that provisions JDKs on-demand. A single instance is used for the whole Kotlin Toolchain execution, so
     * we ensure that invalid `JAVA_HOME` errors are only reported once. We can also benefit from the session-specific
     * cache.
     */
    val jdkProvider: JdkProvider
}
