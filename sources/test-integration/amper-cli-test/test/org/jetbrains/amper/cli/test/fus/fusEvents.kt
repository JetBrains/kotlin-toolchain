/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.generateUnifiedDiff
import org.junit.jupiter.api.AssertionFailureBuilder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private val fusSerializer = FusKotlinSerializer()

/**
 * The result of a CLI run with FUS enabled, with the events that the CLI dumped into [eventsDumpFile] during the run.
 */
class FusCliResult(
    val cliResult: AmperCliResult,
    private val eventsDumpFile: Path,
) {
    private val eventsByTag by lazy { readDumpedEvents() }

    /**
     * The FUS events that the CLI reported during this run, in the order in which they were reported.
     *
     * Note that these are the events that the CLI reports, which is not necessarily what is sent to the FUS servers:
     * the FUS client drops the events that it cannot validate against the FUS metadata.
     * See [validatedFusEvents] for the events as they are effectively sent.
     */
    val reportedFusEvents: List<LogEvent> by lazy { eventsByTag["reported"] ?: [] }

    /**
     * The FUS events as the FUS client recorded them, after anonymization and validation against the FUS metadata.
     * This is what is effectively sent to the FUS servers.
     *
     * Compared to [reportedFusEvents], the identifiers are anonymized, and the values that the FUS metadata doesn't
     * describe are replaced by `validation.*` markers. Events whose group the metadata doesn't describe at all are
     * missing from this list.
     *
     * Beware: the FUS client doesn't reliably record the last event of a run (usually `ktc.command.result`) before the
     * CLI exits, so don't assert on it from this list.
     */
    val validatedFusEvents: List<LogEvent> by lazy { eventsByTag["validated"] ?: [] }

    private fun readDumpedEvents(): Map<String, List<LogEvent>> {
        if (!eventsDumpFile.exists()) {
            fail("The CLI didn't dump any FUS event: no file was created at $eventsDumpFile")
        }
        return eventsDumpFile.readLines()
            .map { dumpLineRegex.matchEntire(it) ?: error("couldn't parse FUS event dump line: $it") }
            .map { match ->
                val tag = match.groups["tag"]?.value ?: error("missing group 'tag'")
                val eventJson = match.groups["eventJson"]?.value ?: error("missing group 'eventJson'")
                tag to fusSerializer.fromJson(eventJson, LogEvent::class)
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    companion object {
        // See FusEventDumpFile.dump for the format definition
        private val dumpLineRegex = Regex("""^\[pid-\d+]\[(?<tag>[^]]+)] (?<eventJson>.*)""")
    }
}

/** The ID of the event itself, such as `ktc.command.invoked`. */
internal val LogEvent.eventId: String get() = event.id

/** The data of the event, with plain Kotlin values (strings, booleans, numbers, and lists thereof). */
internal val LogEvent.data: Map<String, Any> get() = event.data

/** This event serialized as JSON, the way the FUS client sends it. */
internal fun LogEvent.toJson(): String = fusSerializer.toJson(this)

/**
 * The shape of the identifiers that the FUS client anonymizes with the local salt, as described by the `hash` regex
 * of the FUS metadata. The `#S`/`#C` suffixes tell the FUS servers which salt was used.
 */
private val AnonymizedIdRegex = Regex("""[0-9a-f]{64}(#[SC])+""")

/**
 * Asserts that the given [idValue] was anonymized by the FUS client, and thus cannot be traced back to its original value.
 * The [idName] is only used in the failure message.
 */
internal fun assertIsAnonymizedId(idValue: String?, idName: String) {
    assertNotNull(idValue, "The '$idName' identifier is missing from the FUS event")
    assertTrue(AnonymizedIdRegex.matches(idValue), "The '$idName' identifier should be anonymized, but was '$idValue'")
}

/**
 * Asserts that the data of this event is exactly the given [expectedData], apart from the [ignoredKeys].
 */
internal fun LogEvent.assertData(vararg expectedData: Pair<String, Any>, ignoredKeys: Set<String> = emptySet()) {
    val actual = data - ignoredKeys
    val expectedLines = expectedData.map { "${it.first}=${it.second}" }.sorted()
    val actualLines = actual.map { "${it.key}=${it.value}" }.sorted()
    if (expectedData.toMap() != actual) {
        AssertionFailureBuilder.assertionFailure()
            .message("Unexpected data in the '$eventId' FUS event. Diff:\n${generateUnifiedDiff(expectedLines, "expected", actualLines, "actual")}")
            .expected(expectedLines.joinToString("\n"))
            .actual(actualLines.joinToString("\n"))
            .buildAndThrow()
    }
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
    " No FUS events at all in this list."
} else {
    joinToString(prefix = " The events in this list were:\n", separator = "\n") { "  - ${fusSerializer.toJson(it)}" }
}
