/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.testevents

import kotlinx.serialization.Serializable
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.serialization.paths.SerializablePath
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An event emitted while a test run is executing.
 */
@Serializable
sealed interface TestEvent : OperationScopedEvent.DomainEvent {
    /**
     * [TestId] that is associated with this event; `null` if there is no particular test associated.
     */
    val testId: TestId?
}

/**
 * A [TestEvent] that is associated with a test by a [testId].
 */
@Serializable
sealed interface TestEventWithId : TestEvent {
    override val testId: TestId
}

/**
 * A [TestEvent] that contains the test [descriptor].
 * Such events are issued when the test is mentioned the first time.
 */
@Serializable
sealed interface TestEventWithDescriptor : TestEventWithId {
    val descriptor: TestDescriptor

    override val testId: TestId
        get() = descriptor.id
}

/**
 * A non-blank identifier that __uniquely__ identifies a test or test suite within a test run.
 */
@JvmInline
@Serializable
value class TestId(val value: String) {
    init {
        require(value.isNotBlank()) { "Test ID must not be blank" }
    }
}

/**
 * A hint for locating a test in its source code or an external resource.
 */
@Serializable
sealed interface TestLocationHint {

    /**
     * Locates a test suite by its fully qualified class name.
     */
    @Serializable
    data class Class(val className: String) : TestLocationHint

    /**
     * Locates a test by its class name, method name, and optional parameter types.
     */
    @Serializable
    data class Method(
        val className: String,
        val methodName: String,
        val parameterTypes: List<String> = emptyList(),
    ) : TestLocationHint
    /**
     * Locates a test using a URI.
     */
    @Serializable
    data class Uri(val value: String) : TestLocationHint
}

/**
 * Describes a test or test suite reported during a test run.
 */
@Serializable
data class TestDescriptor(
    val id: TestId,
    val parentId: TestId?,
    val displayName: String,
    val location: TestLocationHint? = null,
    /**
     * The TeamCity-formatted name, used solely as a workaround so TeamCity correctly categorizes tests.
     */
    val teamCityName: String = displayName,
)

/**
 * Signals that a test suite has started.
 */
@Serializable
data class TestSuiteStarted(override val descriptor: TestDescriptor) : TestEventWithDescriptor

/**
 * Signals that a test suite has been aborted.
 */
@Serializable
data class TestSuiteAborted(
    override val testId: TestId,
    val duration: Duration?,
    val abortMessage: String,
) : TestEventWithId

/**
 * Signals that a test suite failed.
 */
@Serializable
data class TestSuiteFailed(
    override val testId: TestId,
    val duration: Duration?,
    val failureMessage: String,
    val stackTrace: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val expectedFilePath: SerializablePath? = null,
    val actualFilePath: SerializablePath? = null,
) : TestEventWithId

/**
 * Signals that a test suite has finished.
 */
@Serializable
data class TestSuiteFinished(
    override val testId: TestId,
    val duration: Duration? = null,
) : TestEventWithId

/**
 * Signals that a test suite has been skipped.
 *
 * There is no [TestSuiteStarted] or [TestSuiteFinished] for this event—it's self-contained.
 */
@Serializable
data class TestSuiteSkipped(
    override val descriptor: TestDescriptor,
    val reason: String,
) : TestEventWithDescriptor

/**
 * Signals that an individual test has started.
 */
@Serializable
data class TestStarted(override val descriptor: TestDescriptor) : TestEventWithDescriptor

/**
 * Signals that an individual test has been skipped.
 *
 * There is no [TestStarted] or [TestFinished] for this event—it's self-contained.
 */
@Serializable
data class TestSkipped(override val descriptor: TestDescriptor, val reason: String) : TestEventWithDescriptor

/**
 * Signals that an individual test has finished.
 */
@Serializable
sealed interface TestFinished : TestEventWithId {
    override val testId: TestId
    val duration: Duration?

    /**
     * Signals that a test completed successfully.
     */
    @Serializable
    data class Succeeded(override val testId: TestId, override val duration: Duration?) : TestFinished

    /**
     * Signals that a test completed with a failure.
     */
    @Serializable
    data class Failed(
        override val testId: TestId,
        override val duration: Duration?,
        val failureMessage: String,
        val stackTrace: String? = null,
        val expected: String? = null,
        val actual: String? = null,
        val expectedFilePath: SerializablePath? = null,
        val actualFilePath: SerializablePath? = null,
    ) : TestFinished

    /**
     * Signals that a test was aborted.
     */
    @Serializable
    data class Aborted(
        override val testId: TestId,
        override val duration: Duration?,
        val abortMessage: String,
    ) : TestFinished
}

/**
 * Contains standard output produced by a test, or unattributed output from the test process.
 */
@Serializable
data class TestStdoutEvent(
    override val testId: TestId?,
    val text: String,
) : TestEvent

/**
 * Contains standard error output produced by a test, or unattributed output from the test process.
 */
@Serializable
data class TestStderrEvent(
    override val testId: TestId?,
    val text: String,
) : TestEvent

/**
 * Contains a timestamped key-value report entry produced by a test.
 */
@Serializable
data class TestReportEvent(
    override val testId: TestId,
    val key: String,
    val value: String,
    val mediaType: String? = null,
    val timestamp: Instant? = null,
) : TestEventWithId
