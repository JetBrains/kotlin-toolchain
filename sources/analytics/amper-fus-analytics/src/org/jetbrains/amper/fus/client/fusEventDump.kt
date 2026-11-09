/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * The file where the FUS events should be dumped, or null if no dump was requested.
 *
 * Dumping the events allows inspecting them for debugging purposes, and is how our own tests exercise the FUS
 * reporting. Nothing has to be sent to the FUS servers for the events to be dumped.
 */
internal val fusEventDumpFile: FusEventDumpFile? = System.getenv("KOTLIN_TOOLCHAIN_FUS_DUMP_FILE")
    ?.let { FusEventDumpFile(Path(it)) }

/**
 * A file where the FUS events of this process are written as they are reported and recorded, one event per line.
 *
 * The events are appended to the file, so the dumps of multiple runs using the same file accumulate.
 */
internal class FusEventDumpFile(private val path: Path) {

    private val serializer = FusKotlinSerializer()

    private val pid = ProcessHandle.current().pid()

    /**
     * Dumps the [event] that we just reported, exactly as we built it.
     *
     * Every reported event is dumped this way, even if the FUS client later drops it. A reported event without the
     * matching validated one means the event never made it to the event log, which is what happens when the FUS
     * metadata doesn't describe its group (for instance for groups that are not published yet).
     */
    fun dumpReportedEvent(event: LogEvent) = dump(tag = "reported", event)

    /**
     * Dumps the [event] as it was recorded by the FUS client, after anonymization and validation.
     *
     * This is what is effectively sent to the FUS servers. Values that the FUS metadata doesn't describe appear here
     * as `validation.*` markers instead of the reported values. See [dumpReportedEvent] for the initial version.
     */
    fun dumpValidatedEvent(event: LogEvent) = dump(tag = "validated", event)

    /**
     * Dumps the [eventJson] as it was sent by the FUS client to the server.
     */
    fun dumpSentEvent(eventJson: String) = dump(tag = "sent", eventJson)

    @Synchronized
    private fun dump(tag: String, event: LogEvent) {
        dump(tag, serializer.toJson(event))
    }

    @Synchronized
    private fun dump(tag: String, eventJson: String) {
        val line = "[pid-$pid][$tag] $eventJson\n"
        path.createParentDirectories().writeText(line, Charsets.UTF_8, CREATE, APPEND)
    }
}
