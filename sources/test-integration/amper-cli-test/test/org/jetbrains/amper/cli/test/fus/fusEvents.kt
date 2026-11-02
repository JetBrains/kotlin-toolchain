/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import org.jetbrains.amper.test.AmperCliResult
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The marker of the debug logs that the CLI writes for each reported FUS event, followed by the event as JSON.
 * See `logReportedFusEvent` in the `amper-fus-analytics` module.
 */
private const val REPORTED_FUS_EVENT_LOG_PREFIX = "[FUS][reported] "

private val fusSerializer = FusKotlinSerializer()

/**
 * The FUS events that the CLI reported during this run, in the order in which they were reported.
 *
 * Note that these are the events that the CLI reports, which is not necessarily what is sent to the FUS servers:
 * the FUS client drops the events that it cannot validate against the FUS metadata.
 *
 * This requires the CLI to have been run with FUS debug logging enabled and with a debug console log level.
 */
internal val AmperCliResult.reportedFusEvents: List<LogEvent>
    get() = stdout.lineSequence()
        // The CLI prints these logs through Mordant, which may add a level prefix or styling around them.
        .mapNotNull { line ->
            line.substringAfter(REPORTED_FUS_EVENT_LOG_PREFIX, missingDelimiterValue = "").ifEmpty { null }
        }
        .map { fusSerializer.fromJson(it.trim(), LogEvent::class) }
        .toList()

/** The ID of the event itself, such as `ktc.command.invoked`. */
internal val LogEvent.eventId: String get() = event.id

/** The data of the event, with plain Kotlin values (strings, booleans, numbers, and lists thereof). */
internal val LogEvent.data: Map<String, Any> get() = event.data

/**
 * Asserts that the data of this event is exactly the given [expectedData], apart from the [ignoredKeys].
 */
internal fun LogEvent.assertData(vararg expectedData: Pair<String, Any>, ignoredKeys: Set<String> = emptySet()) {
    assertEquals(expectedData.toMap(), data - ignoredKeys, "Unexpected data in the '$eventId' FUS event")
}

/**
 * Returns the single FUS event with the given [eventId] among these events, and fails if there isn't exactly one.
 */
internal fun List<LogEvent>.assertSingle(eventId: String): LogEvent {
    val matching = filter { it.eventId == eventId }
    return matching.singleOrNull()
        ?: fail("Expected exactly one '$eventId' FUS event, but got ${matching.size}.${describeAll()}")
}

/**
 * Asserts that no FUS event with the given [eventId] was reported.
 */
internal fun List<LogEvent>.assertNone(eventId: String) {
    if (any { it.eventId == eventId }) {
        fail("Expected no '$eventId' FUS event, but found some.${describeAll()}")
    }
}

/**
 * Asserts that exactly the given [eventIds] were reported, in that order.
 */
internal fun List<LogEvent>.assertEventIds(vararg eventIds: String) {
    assertEquals(eventIds.toList(), map { it.eventId }, "Unexpected list of reported FUS events")
}

private fun List<LogEvent>.describeAll(): String = if (isEmpty()) {
    " No FUS event was reported at all."
} else {
    joinToString(prefix = " The reported events were:\n", separator = "\n") { "  - ${fusSerializer.toJson(it)}" }
}
