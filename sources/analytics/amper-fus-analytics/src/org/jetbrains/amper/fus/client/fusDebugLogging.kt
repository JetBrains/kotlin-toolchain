/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FUS-events")

private val serializer by lazy { FusKotlinSerializer() }

/**
 * Logs the [event] that we just reported, exactly as we built it.
 *
 * Every reported event is logged this way, even if the FUS client later drops it. A `reported` log line without the
 * matching [logValidatedFusEvent] line means the event never made it to the event log, which is what happens when
 * the FUS metadata doesn't describe its group (for instance for groups that are not published yet).
 */
internal fun logReportedFusEvent(event: LogEvent) {
    logger.debug("[FUS][reported] {}", serializer.toJson(event))
}

/**
 * Logs the [event] as it was recorded by the FUS client, after anonymization and validation.
 *
 * This is what is effectively sent to the FUS servers. Values that the FUS metadata doesn't describe appear here as
 * `validation.*` markers instead of the reported values. See [logReportedFusEvent] for the initial version.
 */
internal fun logValidatedFusEvent(event: LogEvent) {
    logger.debug("[FUS][validated] {}", serializer.toJson(event))
}
