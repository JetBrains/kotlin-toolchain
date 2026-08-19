/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.events

import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.NoopEventSink
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.plus

/**
 * Chain-of-responsibility type of interface
 * that allows contributing scoped [org.jetbrains.amper.events.sink.EventSink] implementations to composite together.
 *
 * @see createGlobalSink
 * @see createOperationSink
 */
interface EventSinkContributor {
    /**
     * Provides the global sink if necessary
     */
    fun createGlobalSink(): GlobalEventSink = NoopEventSink

    /**
     * Provides the operation-scoped sink if necessary
     */
    fun createOperationSink(): OperationEventSink = NoopEventSink
}

typealias EventSinkContributors = List<EventSinkContributor>

fun EventSinkContributors.createGlobalSink(): GlobalEventSink =
    map(EventSinkContributor::createGlobalSink).fold(NoopEventSink, GlobalEventSink::plus)

fun EventSinkContributors.createOperationSink(): OperationEventSink =
    map(EventSinkContributor::createOperationSink).fold(NoopEventSink, OperationEventSink::plus)
