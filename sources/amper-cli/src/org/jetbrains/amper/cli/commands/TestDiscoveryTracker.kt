/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether any test was discovered during a test run.
 */
internal class TestDiscoveryTracker : EventSink<OperationScopedEvent.DomainEvent> {

    private val _testsDiscovered = AtomicBoolean(false)

    /**
     * Whether any test (even a skipped one) was discovered so far.
     */
    val testsDiscovered: Boolean get() = _testsDiscovered.get()

    override fun emit(event: OperationScopedEvent.DomainEvent) {
        if (event is TestStarted ||
            event is TestSuiteStarted ||
            // skipped tests are still discovered tests
            event is TestSkipped ||
            event is TestSuiteSkipped
        ) {
            _testsDiscovered.set(true)
        }
    }
}
