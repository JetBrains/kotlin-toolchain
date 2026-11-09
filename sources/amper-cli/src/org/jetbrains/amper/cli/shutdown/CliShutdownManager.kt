/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.shutdown

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.amper.stdlib.runtime.runOnJvmShutdown
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import org.tinylog.provider.ProviderRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private class ShutdownCleanup(val name: String, val cleanup: suspend () -> Unit)

/**
 * A way to register cleanup hooks that still have access to telemetry and logging.
 */
object CliShutdownManager {

    private val logger = LoggerFactory.getLogger(CliShutdownManager::class.java)

    init {
        runOnJvmShutdown {
            shutdownCli()
        }
    }

    private val cleanups: MutableList<ShutdownCleanup> = CopyOnWriteArrayList()

    @Volatile
    private var closeOpenTelemetry: () -> Unit = {}

    @Volatile
    private var shutdownStarted = false

    /**
     * Registers a cleanup function that runs on shutdown, but with logging and telemetry available.
     *
     * Regular shutdown hooks run concurrently with the shutdown of the telemetry and logging systems, so they can't use
     * telemetry or logging (otherwise we see the dreaded LOGGER ERROR on stderr). This is a proper way to do it.
     */
    fun registerCleanup(name: String, block: suspend () -> Unit) {
        val cleanup = ShutdownCleanup(name, block)
        if (shutdownStarted) {
            error("Shutdown already started, cannot register new cleanup '$name'")
        }
        cleanups.add(cleanup)
    }

    fun registerOpenTelemetryCleanup(closeOpenTelemetry: () -> Unit) {
        this.closeOpenTelemetry = closeOpenTelemetry
    }

    private fun shutdownCli() {
        shutdownStarted = true

        runBlocking {
            runRegisteredCleanups()
        }

        try {
            closeOpenTelemetry()
        } catch (e: Exception) {
            logger.error("Exception during OpenTelemetry shutdown: ${e.message}", e)
        }

        // Manual Tinylog shutdown after all coroutines are canceled and done
        // (we don't want more logging to happen after shutting down the logging system)
        ProviderRegistry.getLoggingProvider().shutdown()
    }

    private suspend fun runRegisteredCleanups() {
        // We avoid parallelism on purpose for simplicity, because there is effectively only one cleanup right now.
        // We also handle invididual timeouts for each cleanup so we can identify easier which is hanging.
        cleanups.forEach { cleanup ->
            withTimeoutOrNull(2.seconds) {
                spanBuilder(cleanup.name).use {
                    try {
                        cleanup.cleanup()
                    } catch (e: Exception) {
                        logger.error("Exception during shutdown cleanup '${cleanup.name}': ${e.message}", e)
                    }
                }
            } ?: run {
                logger.error("Timeout during shutdown cleanup '${cleanup.name}'")
                // TODO consider thread/coroutines dump here
            }
        }
    }
}
