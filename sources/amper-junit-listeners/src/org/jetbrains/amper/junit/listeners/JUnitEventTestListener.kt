/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.listeners

import org.jetbrains.amper.junit.event.JUnitEventProtocol
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.FileEntry
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.engine.support.descriptor.UriSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.opentest4j.AssertionFailedError
import org.opentest4j.FileInfo
import java.io.PrintStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Service-loaded producer of the private structured JUnit protocol.
 *
 * @see org.jetbrains.amper.junit.event.JUnitEventProtocol
 */
@Suppress("unused") // Used by ServiceLoader
class JUnitEventTestListener(
    // We need to select and store the stream early (even System.out) to prevent JUnit
    // from capturing our event messages as test output.
    private val protocolStream: PrintStream = System.out, // default used when loaded via ServiceLoader
) : TestExecutionListener {
    private val currentTestPlan = InheritableThreadLocal<TestPlan?>()
    private val currentTest = InheritableThreadLocal<TestIdentifier?>()
    private val ignoredRootContainers = mutableSetOf<UniqueId>()

    /**
     * The time at which each test or container started, used to calculate its duration.
     */
    private val startTimes = ConcurrentHashMap<UniqueId, TimeMark>()

    private val stdout = threadAwarePrintStream(threadLocalKey = currentTest) { test, text ->
        emit(JUnitEventProtocol.Event.TestStdout(test?.uniqueId, text))
    }

    private val stderr = threadAwarePrintStream(threadLocalKey = currentTest) { test, text ->
        emit(JUnitEventProtocol.Event.TestStderr(test?.uniqueId, text))
    }

    init {
        // we don't use the built-in stream capture of JUnit because it only reports it at the end of each test
        // (it doesn't stream it). See https://github.com/junit-team/junit5/issues/4317
        System.setOut(stdout)
        System.setErr(stderr)
    }

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        // We don't want to have engine containers in the tree output of the run,
        // as they introduce extra nesting level without adding much to the semantic grouping of the tests.
        ignoredRootContainers += testPlan.roots.map { it.uniqueIdObject }
        currentTestPlan.set(testPlan)
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (shouldIgnore(testIdentifier)) return

        val id = testIdentifier.uniqueId
        val parentId = testIdentifier.parentId()
        val displayName = testIdentifier.displayName
        val location = testIdentifier.location()
        val teamCityName = testIdentifier.teamCityName
        currentTest.set(testIdentifier)
        markStart(testIdentifier)

        @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA") // TestIdentifier can't be constructed with the `null` `type`
        when (testIdentifier.type) {
            TestDescriptor.Type.CONTAINER -> emit(
                JUnitEventProtocol.Event.SuiteStarted(
                    id = id,
                    parentId = parentId,
                    displayName = displayName,
                    location = location,
                    teamCityName = teamCityName,
                )
            )
            TestDescriptor.Type.TEST,
            TestDescriptor.Type.CONTAINER_AND_TEST,
                -> {
                emit(
                    JUnitEventProtocol.Event.TestStarted(
                        id = id,
                        parentId = parentId,
                        displayName = displayName,
                        location = location,
                        teamCityName = teamCityName,
                    )
                )
            }
        }
    }

    private fun markStart(testIdentifier: TestIdentifier) {
        startTimes[testIdentifier.uniqueIdObject] = TimeSource.Monotonic.markNow()
    }

    override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
        if (shouldIgnore(testIdentifier)) return

        val id = testIdentifier.uniqueId
        val parentId = testIdentifier.parentId()
        val displayName = testIdentifier.displayName
        val location = testIdentifier.location()
        val teamCityName = testIdentifier.teamCityName

        if (testIdentifier.type == TestDescriptor.Type.CONTAINER) {
            emit(
                JUnitEventProtocol.Event.SuiteSkipped(
                    id = id,
                    parentId = parentId,
                    displayName = displayName,
                    location = location,
                    teamCityName = teamCityName,
                    reason = reason
                )
            )
        } else {
            emit(
                JUnitEventProtocol.Event.TestSkipped(
                    id = id,
                    parentId = parentId,
                    displayName = displayName,
                    location = location,
                    teamCityName = teamCityName,
                    reason = reason
                )
            )
        }
    }

    override fun executionFinished(testIdentifier: TestIdentifier, result: TestExecutionResult) {
        if (shouldIgnore(testIdentifier)) return
        // make sure partial output at the end of the tests is reported
        stdout.flush()
        stderr.flush()

        val parent = currentTestPlan.get()
            ?.getParent(testIdentifier)
            ?.getOrNull()
            ?.takeIf { it.uniqueIdObject !in ignoredRootContainers }
        // Restore parent identifier as currentTest
        currentTest.set(parent)

        val duration = startTimes.remove(testIdentifier.uniqueIdObject)?.elapsedNow()?.inWholeMilliseconds
        val throwable = result.throwable.getOrNull()

        if (testIdentifier.type == TestDescriptor.Type.CONTAINER) {
            when (result.status) {
                TestExecutionResult.Status.SUCCESSFUL ->
                    emit(JUnitEventProtocol.Event.SuiteFinished(testIdentifier.uniqueId, duration))
                TestExecutionResult.Status.ABORTED ->
                    emit(JUnitEventProtocol.Event.SuiteAborted(testIdentifier.uniqueId, duration, throwable?.message ?: "Test was aborted"))
                // TODO: Can it happen (if there is exception in the setup e.g.)?
                TestExecutionResult.Status.FAILED -> {}
            }
            return
        }

        when (result.status) {
            TestExecutionResult.Status.SUCCESSFUL -> emit(
                JUnitEventProtocol.Event.Succeeded(
                    testIdentifier.uniqueId,
                    duration
                )
            )
            TestExecutionResult.Status.ABORTED -> emit(
                JUnitEventProtocol.Event.TestAborted(
                    id = testIdentifier.uniqueId,
                    durationMillis = duration,
                    abortMessage = throwable?.message ?: "Test was aborted"
                )
            )
            TestExecutionResult.Status.FAILED -> {
                val assertion = throwable as? AssertionFailedError
                val expected = assertion?.expected?.value
                val actual = assertion?.actual?.value
                emit(
                    JUnitEventProtocol.Event.Failed(
                        id = testIdentifier.uniqueId,
                        durationMillis = duration,
                        failureMessage = throwable?.message ?: "Test failed without exception",
                        stackTrace = throwable?.stackTraceToString(),
                        expected = if (expected is FileInfo) String(expected.contents) else assertion?.expected?.stringRepresentation,
                        actual = if (actual is FileInfo) String(actual.contents) else assertion?.actual?.stringRepresentation,
                        expectedFilePath = (expected as? FileInfo)?.path?.let(Path::of),
                        actualFilePath = (actual as? FileInfo)?.path?.let(Path::of),
                    )
                )
            }
        }
    }

    override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
        if (shouldIgnore(testIdentifier)) return
        entry.keyValuePairs.forEach { (key, value) ->
            // We don't use the standard `stdout` and `stderr` keys (from the stream capture feature of JUnit)
            // in any special way anymore. We can't use them for reporting, because we want to report these
            // line-by-line as they come, but the default stream capture just sends one `stdout` event at the end of the
            // test. It was meant for XML reporting initially. See: https://github.com/junit-team/junit5/issues/4317.
            // Because of this, we have our own standard stream watchers, so we must not report 'stdout'/'stderr'
            // entries, otherwise we'll double the output.
            when (key) {
                // Using custom keys because there is currently no standard key to represent streaming output.
                // See https://github.com/junit-team/junit5/issues/4323
                StreamingOutputKeys.STDOUT -> emit(JUnitEventProtocol.Event.TestStdout(testIdentifier.uniqueId, value))
                StreamingOutputKeys.STDERR -> emit(JUnitEventProtocol.Event.TestStderr(testIdentifier.uniqueId, value))
                else -> emit(
                    JUnitEventProtocol.Event.Report(
                        id = testIdentifier.uniqueId,
                        key = key,
                        value = value,
                        mediaType = null,
                        timestampMillis = entry.timestamp.toEpochMillis(),
                    )
                )
            }
        }
    }

    override fun fileEntryPublished(testIdentifier: TestIdentifier, file: FileEntry) {
        if (shouldIgnore(testIdentifier)) return
        emit(
            JUnitEventProtocol.Event.Report(
                id = testIdentifier.uniqueId,
                key = "attached-file-${file.timestamp}-${file.path.pathString.hashCode()}",
                value = file.path.pathString,
                mediaType = file.mediaType.getOrNull(),
                timestampMillis = file.timestamp.toEpochMillis(),
            )
        )
    }

    private fun emit(event: JUnitEventProtocol.Event) = protocolStream.println(JUnitEventProtocol.encode(event))

    private fun shouldIgnore(test: TestIdentifier) = test.uniqueIdObject in ignoredRootContainers

    private fun TestIdentifier.parentId(): String? = parentIdObject
        ?.getOrNull()
        ?.takeIf { it !in ignoredRootContainers }
        ?.toString()

    private fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun TestIdentifier.location(): JUnitEventProtocol.Location? = when (val source = source.getOrNull()) {
        is ClassSource -> JUnitEventProtocol.Location.Class(source.className)
        is MethodSource -> JUnitEventProtocol.Location.Method(
            source.className,
            source.methodName,
            source.methodParameterTypes?.takeIf { it.isNotBlank() }?.split(',') ?: []
        )
        is UriSource -> JUnitEventProtocol.Location.Uri(source.uri.toString())
        // TODO: There are also other types of sources which can be passed into the dynamic tests.
        //  We can support them later if there is a need.
        else -> null
    }

    /**
     * The name of the test identified by this ID, in the format expected by Teamcity.
     *
     * TeamCity mentions in the documentation:
     *   > A full test name can have a form of:
     *   > `<suite name>: <package/namespace name>.<class name>.<test method>(<test parameters>)`,
     *   > where `<class name>` and `<test method>` cannot have dots in the names.
     *   > Only `<test method>` is a mandatory part of a full test name.
     *
     * However, what is actually useful for the reporting are **arguments**, not **parameters**.
     * Considering, that [TestIdentifier] doesn't have this information (only types), we resort to using
     * [TestIdentifier.displayName] instead of <test method>(<test parameters>) segment as the most user-friendly name.
     *
     * For the better grouping and filtering we also prepend full class name in case the test originates from a class.
     *
     * Relevant TeamCity docs:
     *  * On [test name unicity](https://www.jetbrains.com/help/teamcity/service-messages.html#Nested+Test+Reporting)
     *  * On [the exact test name format](https://www.jetbrains.com/help/teamcity/service-messages.html#Interpreting+Test+Names)
     */
    private val TestIdentifier.teamCityName: String
        get() {
            val parentContainer = parentContainer
            return buildString {
                if (parentContainer != null) {
                    append(parentContainer.teamCityName)
                    append(": ")
                }
                append(sourcePrefix)
                append(displayName)
            }
        }

    private val TestIdentifier.parentContainer: TestIdentifier?
        get() = currentTestPlan.get()
            ?.getParent(this)
            ?.getOrNull()
            // Do not add root containers to TC name.
            ?.takeIf { it.uniqueIdObject !in ignoredRootContainers }

    private val TestIdentifier.sourcePrefix: String
        get() = when (val source = source.getOrNull()) {
            // Prepend package information to separate suites with the same name
            is ClassSource -> {
                val packageName = source.javaClass.packageName
                if (packageName.isNotBlank()) "$packageName." else ""
            }
            // Prepend full class name if the test originates from method
            // to have filtering by package and class in TeamCity.
            is MethodSource -> "${source.className}."
            else -> ""
        }
}
