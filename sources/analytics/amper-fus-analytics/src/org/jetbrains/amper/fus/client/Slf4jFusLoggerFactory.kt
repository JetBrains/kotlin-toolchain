/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.FusLoggerFactory
import com.jetbrains.fus.reporting.Logger
import org.jetbrains.amper.fus.userid.isInternalUserForFUS
import org.slf4j.LoggerFactory

internal object Slf4jFusLoggerFactory : FusLoggerFactory {
    override fun getLogger(name: String): Logger = Slf4jFusLogger(LoggerFactory.getLogger(name))
}

/**
 * Adapter for SLF4J logger to FUS's [Logger].
 */
private class Slf4jFusLogger(private val logger: org.slf4j.Logger) : Logger {
    // INFO -> DEBUG so it's not printed to the console.
    // TODO Revisit when we separate logging from console output better.
    override fun info(message: String) = logger.debug("INFO: $message")

    override fun error(message: String) = if (isInternalUserForFUS) {
        logger.error(message)
    } else {
        // Regular users don't care about FUS errors, so we use DEBUG level to avoid printing them to the console.
        // TODO Revisit when we separate logging from console output better.
        logger.debug("ERROR: $message")
    }

    override fun debug(message: String) = logger.debug(message)
}
