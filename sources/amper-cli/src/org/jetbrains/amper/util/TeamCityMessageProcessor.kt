/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.util

import jetbrains.buildServer.messages.serviceMessages.BaseTestMessage
import jetbrains.buildServer.messages.serviceMessages.BaseTestSuiteMessage
import jetbrains.buildServer.messages.serviceMessages.DefaultServiceMessageVisitor
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageParserCallback
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes
import jetbrains.buildServer.messages.serviceMessages.ServiceMessagesParser
import jetbrains.buildServer.messages.serviceMessages.TestFailed
import jetbrains.buildServer.messages.serviceMessages.TestFinished
import jetbrains.buildServer.messages.serviceMessages.TestIgnored
import jetbrains.buildServer.messages.serviceMessages.TestStarted
import jetbrains.buildServer.messages.serviceMessages.TestStdErr
import jetbrains.buildServer.messages.serviceMessages.TestStdOut
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.slf4j.LoggerFactory
import java.text.ParseException
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.amper.testevents.TestFinished as AmperTestFinished
import org.jetbrains.amper.testevents.TestStarted as AmperTestStarted
import org.jetbrains.amper.testevents.TestSuiteFinished as AmperTestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteStarted as AmperTestSuiteStarted

internal class TeamCityMessageProcessor(
    private val eventSink: EventSink<TestEvent>,
    private val onTestFailed: (id: TestId, message: TestFailed) -> Unit = { _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val smParser = ServiceMessagesParser()
    private val flows = mutableMapOf<FlowId, Flow>()

    // Default Kotlin/Native test runner does not emit flow messages, so we retain the suite stack as a fallback
    // to build parent-child relationship.
    private val nonFlowIdStack = ArrayDeque<TestId>()

    private val runningTests = mutableMapOf<TestId, TestState>()
    private val runningSuites = mutableSetOf<TestId>()

    fun parse(line: String, stderr: Boolean) {
        smParser.parse(line, object : ServiceMessageParserCallback {
            override fun regularText(text: String) {
                // If there was some non-event output, we'll simply associate it with the latest test started.
                val testId = nonFlowIdStack.lastOrNull()
                val eventMessage = "$text${System.lineSeparator()}"
                emit(if (stderr) TestStderrEvent(testId, eventMessage) else TestStdoutEvent(testId, eventMessage))
            }

            override fun serviceMessage(message: ServiceMessage) {
                message.visit(visitor)
            }

            override fun parseException(exception: ParseException, text: String) {
                logger.warn("Failed to parse message $text", exception)
                // Do not emit unparsed message.
            }
        })
    }

    private val visitor = object : DefaultServiceMessageVisitor() {
        override fun visitServiceMessage(message: ServiceMessage) {
            when (message.messageName) {
                ServiceMessageTypes.FLOW_STARTED -> message.flowId?.let(::FlowId)?.let { flowId ->
                    val parentFlowId = message.attributes["parent"]?.let(::FlowId)
                    val parentFlow = flows[parentFlowId]
                    if (parentFlowId != null && parentFlow == null) {
                        logger.warn("Received FlowStarted with the parent ${parentFlowId.value} that is unknown.")
                    }
                    flows[flowId] = Flow(flowId, parentFlowId = parentFlowId)
                }
                ServiceMessageTypes.FLOW_FINSIHED -> message.flowId?.let(::FlowId)?.let { flowId ->
                    val existingFlow = flows[flowId]
                    if (existingFlow == null) {
                        logger.warn("Received FlowFinished for the flow ${flowId.value} that isn't started")
                        return
                    }
                    if (existingFlow.isFinished) {
                        logger.warn("Received FlowFinished for the flow ${flowId.value} that was already finished")
                        return
                    }
                    flows[flowId] = existingFlow.copy(isFinished = true)
                }
            }
        }

        override fun visitTestSuiteStarted(message: TestSuiteStarted) {
            val parentId = message.parentId()
            val testId = message.findTestIdOrAddToNonFlowStack { it.nonEmptySuiteName() }
            runningSuites.add(testId)
            emit(
                AmperTestSuiteStarted(
                    TestDescriptor(
                        id = testId,
                        parentId = parentId,
                        displayName = message.displayName(),
                        location = message.suiteLocationHint()
                    )
                )
            )
        }

        override fun visitTestSuiteFinished(message: TestSuiteFinished) {
            val id = message.testId()
            if (message.flowId == null) {
                val lastStackElement = nonFlowIdStack.lastOrNull()
                if (lastStackElement != id) {
                    logger.warn("Test suite $id without flow finished but it's not the last item on the stack (which is $lastStackElement). Trying to remove it from the middle of the stack.")
                    if (!nonFlowIdStack.remove(id)) {
                        logger.warn("Test suite $id wasn't present in the stack at all.")
                    }
                } else {
                    nonFlowIdStack.removeLast()
                }
            }
            if (!runningSuites.remove(id)) {
                logger.warn("Received suite $id finished for unknown suite")
                return
            }
            emit(AmperTestSuiteFinished(id))
        }

        override fun visitTestStarted(message: TestStarted) {
            val parentId = message.parentId()
            val testId = message.findTestIdOrAddToNonFlowStack { it.testName }
            val state = TestState(testId)
            runningTests[testId] = state
            emit(
                AmperTestStarted(
                    TestDescriptor(
                        id = testId,
                        parentId = parentId,
                        displayName = message.displayName(),
                        location = message.testLocationHint()
                    )
                )
            )
        }

        override fun visitTestFinished(message: TestFinished) {
            val id = message.testId()
            val state = runningTests.remove(id) ?: run {
                logger.warn("Attempt to finish unknown test $id: $message")
                return
            }
            val duration = message.testDuration?.milliseconds
            val event = when {
                state.failure != null -> AmperTestFinished.Failed(
                    testId = state.id,
                    duration = duration,
                    failureMessage = state.failure.message,
                    stackTrace = state.failure.stackTrace,
                    expected = state.failure.expected,
                    actual = state.failure.actual,
                )
                else -> AmperTestFinished.Succeeded(testId = state.id, duration = duration)
            }
            if (message.flowId == null) {
                val lastStackElement = nonFlowIdStack.lastOrNull()
                if (lastStackElement != id) {
                    logger.warn("Test $id without flow finished but it's not the latest item on the stack (which is $lastStackElement). Trying to remove it from the middle of the stack.")
                    if (!nonFlowIdStack.remove(id)) {
                        logger.warn("Test $id wasn't present at the stack at all.")
                    }
                } else {
                    nonFlowIdStack.removeLast()
                }
            }
            emit(event)
        }

        private fun <T : ServiceMessage> T.findTestIdOrAddToNonFlowStack(
            getName: (T) -> String,
        ): TestId {
            val flowId = findBestFlowForMessage()?.id
            val testId = if (flowId != null) {
                TestId(flowId.value)
            } else {
                val testId = nonFlowIdStack.lastOrNull().child(getName(this))
                nonFlowIdStack.addLast(testId)
                testId
            }
            return testId
        }

        override fun visitTestIgnored(message: TestIgnored) {
            val parentId = message.parentId()
            val id = message.findTestIdOrAddToNonFlowStack { it.testName }
            if (nonFlowIdStack.lastOrNull() == id) {
                // Immediately remove the test from the non-flow ID stack if it was added there
                nonFlowIdStack.removeLast()
            }
            emit(
                TestSkipped(
                    descriptor = TestDescriptor(
                        id = id,
                        parentId = parentId,
                        displayName = message.testName,
                    ),
                    reason = message.ignoreComment ?: "Test ignored"
                )
            )
        }

        override fun visitTestFailed(message: TestFailed) {
            val id = message.testId()
            val state = runningTests[id]
            if (state == null) {
                logger.warn("Attempt to record failure for an unknown test $id: $message")
                return
            }
            onTestFailed(id, message)
            runningTests[id] = state.copy(
                failure = Failure(
                    message.failureMessage,
                    message.stacktrace,
                    message.expected,
                    message.actual,
                )
            )
        }

        override fun visitTestStdOut(message: TestStdOut) {
            val id = message.testId()
            emit(TestStdoutEvent(id, message.stdOut))
        }

        override fun visitTestStdErr(message: TestStdErr) {
            val id = message.testId()
            emit(TestStderrEvent(id, message.stdErr))
        }
    }

    private fun ServiceMessage.displayName(): String = attributes["displayName"] ?: when (this) {
        is TestSuiteStarted -> suiteName
        is TestStarted -> testName
        else -> error("Not a start message")
    }

    private fun BaseTestMessage.testId(): TestId {
        val flowId = findBestFlowForMessage()?.id
        if (flowId != null) return TestId(flowId.value)

        return nonFlowIdStack.lastOrNull() ?: TestId(testName)
    }

    private fun BaseTestSuiteMessage.testId(): TestId {
        val flowId = findBestFlowForMessage()?.id
        if (flowId != null) return TestId(flowId.value)

        return nonFlowIdStack.lastOrNull() ?: TestId(nonEmptySuiteName())
    }

    private fun BaseTestSuiteMessage.nonEmptySuiteName(): String =
        suiteName.ifEmpty { "<root>" }

    /**
     * Finds the best flow ID for the given message to report it to.
     *
     * We shouldn't report something to the finished flow because it usually means the attribution mistake
     * (see AMPER-5035 for possible reasons). However, discarding the message completely is also wrong.
     *
     * To tackle this issue, we don't delete flows from the map but just mark them as finished and keep the whole hierarchy in the map.
     * This way we can traverse up the flow tree and find the first non-finished flow and attribute the message to it as the best effort.
     *
     * This workaround is described in AMPER-5036.
     */
    private fun ServiceMessage.findBestFlowForMessage(): Flow? {
        val flowId = flowId?.let(::FlowId) ?: return null
        var currentFlow = flows[flowId]
        if (currentFlow == null) {
            logger.warn("Can't attach message $this to the unknown flow ${flowId.value}")
            return null
        }
        while (currentFlow?.isFinished == true) {
            logger.warn("Can't attach message $this to the finished flow ${flowId.value}. Trying to attach it to its parent ${currentFlow.parentFlow?.id}.")
            currentFlow = currentFlow.parentFlow ?: break
        }
        return currentFlow
    }

    private fun ServiceMessage.suiteLocationHint(): TestLocationHint.Class? {
        val locationHint = attributes["locationHint"]?.removePrefix("ktest:suite://") ?: return null
        return TestLocationHint.Class(locationHint)
    }

    private fun TestStarted.testLocationHint(): TestLocationHint.Method? {
        val location = locationHint?.removePrefix("ktest:test://") ?: return null
        val methodSeparator = location.lastIndexOf('.')
        if (methodSeparator <= 0 || methodSeparator == location.lastIndex) return null
        return TestLocationHint.Method(
            className = location.substring(0, methodSeparator),
            methodName = location.substring(methodSeparator + 1),
        )
    }

    /**
     * Finds parent [TestId] based on a flow.
     *
     * Otherwise, falls back to the last ID on the [nonFlowIdStack].
     */
    private fun ServiceMessage.parentId(): TestId? {
        val flow = findBestFlowForMessage()
        if (flow != null) {
            var parentFlow = flow.parentFlow
            while (parentFlow?.isFinished == true) {
                parentFlow = parentFlow.parentFlow
            }
            if (parentFlow != null) return TestId(parentFlow.id.value)
            if (flow.parentFlowId != null) {
                logger.warn("Failed to find non-finished parent flow for flow $flow")
            }
            return null
        }
        return nonFlowIdStack.lastOrNull()
    }

    private fun emit(event: TestEvent) = eventSink.emit(event)

    private fun TestId?.child(name: String): TestId = if (this != null) TestId("$value.$name") else TestId(name)

    @JvmInline
    private value class FlowId(val value: String)

    private data class Flow(val id: FlowId, val parentFlowId: FlowId?, val isFinished: Boolean = false)

    private val Flow.parentFlow: Flow?
        get() {
            if (parentFlowId == null) return null
            val parentFlow = flows[parentFlowId]
            if (parentFlow == null) {
                logger.warn("Trying to retrieve unknown parentFlow $parentFlowId as parent of flow $this")
            }
            return parentFlow
        }

    private data class TestState(
        val id: TestId,
        val failure: Failure? = null,
    )

    private data class Failure(
        val message: String,
        val stackTrace: String?,
        val expected: String?,
        val actual: String?,
    )
}