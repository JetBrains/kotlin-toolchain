/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import com.github.ajalt.mordant.terminal.Terminal
import jetbrains.buildServer.messages.serviceMessages.MessageWithAttributes
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes
import jetbrains.buildServer.messages.serviceMessages.TestFailed
import jetbrains.buildServer.messages.serviceMessages.TestIgnored
import jetbrains.buildServer.messages.serviceMessages.TestStdErr
import jetbrains.buildServer.messages.serviceMessages.TestStdOut
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteFailed
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.uuid.Uuid
import jetbrains.buildServer.messages.serviceMessages.TestFinished as TeamCityTestFinished
import jetbrains.buildServer.messages.serviceMessages.TestStarted as TeamCityTestStarted
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished as TeamCitySuiteFinished
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted as TeamCitySuiteStarted

/**
 * Renders Kotlin Toolchain test events as TeamCity service messages.
 *
 * @see ServiceMessage
 */
internal class TeamCityRenderer(
    private val terminal: Terminal,
) : TestEventRenderer {
    private val descriptors = mutableMapOf<TestId, TestDescriptor>()
    /**
     * This ID is added to the flow ID to guarantee the uniqueness of IDs across multiple runs under single CLI invocation.
     *
     * @see TestId.flowId
     */
    private val testRunId = Uuid.random()

    override fun render(event: TestEvent) {
        when (event) {
            is TestSuiteStarted -> renderStarted(event.descriptor) { name, locationHint ->
                TeamCitySuiteStarted(name).withPresentation(
                    event.descriptor.id,
                    locationHint,
                    event.descriptor.displayName
                )
            }
            is TestStarted -> renderStarted(event.descriptor) { name, locationHint ->
                // disable automatic stdout/stderr capture between start&stop messages
                // (we use proper service messages to report it, so no need for guesswork)
                val captureStdOut = false
                TeamCityTestStarted(name, captureStdOut, locationHint).withPresentation(
                    event.descriptor.id,
                    locationHint,
                    event.descriptor.displayName
                )
            }
            is TestSuiteFinished -> {
                emit(
                    TeamCitySuiteFinished(name(event.testId)).withPresentation(
                        event.testId,
                        null,
                        displayName(event.testId)
                    )
                )
                emit(flowFinished(event.testId))
            }
            is TestSuiteAborted -> finishIgnoredSuite(event.testId, event.abortMessage)
            is TestSuiteFailed -> {
                val syntheticTest = TestDescriptor(
                    id = TestId("${event.testId.value}/<suite>"),
                    parentId = event.testId,
                    displayName = "<suite>",
                    teamCityName = "${name(event.testId)}: <suite>",
                )
                render(TestStarted(syntheticTest))
                render(
                    TestFinished.Failed(
                        testId = syntheticTest.id,
                        duration = event.duration,
                        failureMessage = event.failureMessage,
                        stackTrace = event.stackTrace,
                        expected = event.expected,
                        actual = event.actual,
                        expectedFilePath = event.expectedFilePath,
                        actualFilePath = event.actualFilePath,
                    )
                )
                render(TestSuiteFinished(event.testId, event.duration))
            }
            is TestSuiteSkipped -> {
                // We have to emit test suite started message for TC
                renderStarted(event.descriptor) { name, locationHint ->
                    TeamCitySuiteStarted(name).withPresentation(
                        event.descriptor.id,
                        locationHint,
                        event.descriptor.displayName,
                    )
                }
                finishIgnoredSuite(event.descriptor.id, event.reason)
            }
            is TestFinished.Succeeded -> finish(event.testId, event.duration?.inWholeMilliseconds?.toInt() ?: 0)
            is TestFinished.Aborted -> {
                emit(
                    TestIgnored(name(event.testId), event.abortMessage).withPresentation(
                        event.testId,
                        null,
                        displayName(event.testId),
                    )
                )
                finish(event.testId, event.duration?.inWholeMilliseconds?.toInt() ?: 0)
            }
            is TestSkipped -> {
                // TeamCity also accepts ignored tests without start/finish, but emitting the full lifecycle preserves
                // the location information sent in the start message.
                renderStarted(event.descriptor) { name, locationHint ->
                    TeamCityTestStarted(name, false, locationHint).withPresentation(
                        event.descriptor.id,
                        locationHint,
                        event.descriptor.displayName,
                    )
                }
                emit(
                    TestIgnored(name(event.descriptor.id), event.reason).withPresentation(
                        event.descriptor.id,
                        null,
                        event.descriptor.displayName,
                    )
                )
                finish(event.descriptor.id, 0)
            }
            is TestFinished.Failed -> {
                emit(
                    TestFailed(
                        name(event.testId),
                        event.stackTrace?.lineSequence()?.firstOrNull() ?: event.failureMessage
                    )
                        .withAdditionalAttributes(
                            buildMap {
                                event.stackTrace?.let { put("details", it) }
                                event.actual?.let { put("actual", it) }
                                event.expected?.let { put("expected", it) }
                                // custom IntelliJ attributes defined in
                                // com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor#ATTR_KEY_EXPECTED_FILE_PATH
                                // com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor#ATTR_KEY_ACTUAL_FILE_PATH
                                // they are used by the IDE to display file paths on top of the diff window.
                                // TODO: Can be removed after IDE starts working with test events instead of service messages
                                event.expectedFilePath?.let { put("expectedFile", it.invariantSeparatorsPathString) }
                                event.actualFilePath?.let { put("actualFile", it.invariantSeparatorsPathString) }
                            },
                        )
                        .withPresentation(event.testId, null, displayName(event.testId)),
                )
                finish(event.testId, event.duration?.inWholeMilliseconds?.toInt() ?: 0)
            }
            is TestStdoutEvent -> {
                val testId = event.testId
                if (testId == null) {
                    // TODO: Attribute output without a test ID to the root flow of its test session.
                    emit(TestStdOut("", event.text))
                } else {
                    emit(
                        TestStdOut(name(testId), event.text)
                            .withPresentation(testId, null, displayName(testId)),
                    )
                }
            }
            is TestStderrEvent -> {
                val testId = event.testId
                if (testId == null) {
                    // TODO: Attribute output without a test ID to the root flow of its test session.
                    emit(TestStdErr("", event.text))
                } else {
                    emit(
                        TestStdErr(name(testId), event.text)
                            .withPresentation(testId, null, displayName(testId)),
                    )
                }
            }
            is TestReportEvent -> emit(testMetadata(event))
        }
    }

    private fun renderStarted(
        descriptor: TestDescriptor,
        message: (name: String, locationHint: String?) -> MessageWithAttributes,
    ) {
        descriptors[descriptor.id] = descriptor
        emit(flowStarted(descriptor.id, descriptor.parentId))
        emit(message(descriptor.teamCityName, descriptor.location?.asTeamCityHint()))
    }

    private fun finish(testId: TestId, durationMillis: Int) {
        emit(TeamCityTestFinished(name(testId), durationMillis).withPresentation(testId, null, displayName(testId)))
        emit(flowFinished(testId))
        descriptors.remove(testId)
    }

    private fun finishIgnoredSuite(testId: TestId, reason: String) {
        emit(
            TestIgnored(name(testId), reason).withPresentation(
                testId,
                null,
                displayName(testId),
            )
        )
        emit(TeamCitySuiteFinished(name(testId)).withPresentation(testId, null, displayName(testId)))
        emit(flowFinished(testId))
        descriptors.remove(testId)
    }

    private fun name(testId: TestId): String = descriptors[testId]?.teamCityName ?: testId.value

    private fun displayName(testId: TestId): String = descriptors[testId]?.displayName ?: testId.value

    private fun emit(message: ServiceMessage) = terminal.rawPrint(message.asString() + System.lineSeparator())

    private fun MessageWithAttributes.withAdditionalAttributes(attributes: Map<String, String>): MessageWithAttributes =
        object : MessageWithAttributes(
            messageName,
            this@withAdditionalAttributes.attributes + attributes,
        ) {}

    private fun MessageWithAttributes.withPresentation(
        testId: TestId,
        locationHint: String?,
        displayName: String,
    ): MessageWithAttributes = object : MessageWithAttributes(
        messageName,
        buildMap {
            putAll(this@withPresentation.attributes)
            put("flowId", testId.flowId())
            if ("locationHint" !in this@withPresentation.attributes) {
                locationHint?.let { put("locationHint", it) }
            }
            put("displayName", displayName)
        },
    ) {}

    /**
     * Represents a "flow started" message as defined in
     * [the TeamCity docs](https://www.jetbrains.com/help/teamcity/2024.12/service-messages.html#Message+FlowId).
     */
    private fun flowStarted(id: TestId, parentId: TestId?): ServiceMessage = object : MessageWithAttributes(
        ServiceMessageTypes.FLOW_STARTED,
        buildMap {
            put("flowId", id.flowId())
            parentId?.let { put("parent", it.flowId()) }
        },
    ) {}

    /**
     * Represents a "flow finished" message as defined in
     * [the TeamCity docs](https://www.jetbrains.com/help/teamcity/2024.12/service-messages.html#Message+FlowId).
     */
    private fun flowFinished(id: TestId): ServiceMessage = object : MessageWithAttributes(
        ServiceMessageTypes.FLOW_FINSIHED,
        mapOf("flowId" to id.flowId()),
    ) {}

    /**
     * Represents a test metadata message as defined in
     * [the TeamCity docs](https://www.jetbrains.com/help/teamcity/2024.12/reporting-test-metadata.html).
     */
    private fun testMetadata(event: TestReportEvent): ServiceMessage = object : MessageWithAttributes(
        ServiceMessageTypes.TEST_METADATA,
        buildMap {
            put("testName", name(event.testId))
            put("name", event.key)
            put("value", event.value)
            put("flowId", event.testId.flowId())
            event.mediaType?.let { put("type", it) }
        },
    ) {}

    private fun TestId.flowId(): String = "$value-$testRunId"

    /**
     * This format is based on the one expected by `com.intellij.execution.testframework.JavaLocator` from IntelliJ.
     */
    private fun TestLocationHint.asTeamCityHint(): String = when (this) {
        is TestLocationHint.Class -> "java:suite://$className"
        is TestLocationHint.Method -> buildString {
            append("java:test://$className/$methodName")
            parameterTypes.takeIf { it.isNotEmpty() }?.let { append("[${it.joinToString(",")}]") }
        }
        // IntelliJ also supports file:// protocol out-of-box.
        is TestLocationHint.Uri -> value
    }
}
