/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.FusLoggerFactory
import com.jetbrains.fus.reporting.Logger
import org.slf4j.LoggerFactory

internal object Slf4jFusLoggerFactory : FusLoggerFactory {
    override fun getLogger(name: String): Logger = Slf4jFusLogger(LoggerFactory.getLogger(name))
}

private class Slf4jFusLogger(private val logger: org.slf4j.Logger) : Logger {
    // INFO/ERROR -> DEBUG so it's not printed to the console.
    // TODO Revisit when we separate logging from console output better.
    override fun info(message: String) = logger.debug("INFO: $message")
    override fun error(message: String) = logger.debug("ERROR: $message")
    override fun debug(message: String) = logger.debug(message)
}
