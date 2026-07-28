/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.event

import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * The private, line-oriented protocol between the distributed JUnit listener and the CLI.
 *
 * It deliberately has no serialization-library dependency because this code is put on user test classpaths which
 * we don't want to interfere with.
 */
object JUnitEventProtocol {
    private const val PREFIX = "@@ktc-junit:"

    sealed interface Event {
        data class TestStdout(val id: String?, val text: String) : Event {
            companion object {
                const val TYPE = "testStdout"
            }
        }

        data class TestStderr(val id: String?, val text: String) : Event {
            companion object {
                const val TYPE = "testStderr"
            }
        }

        data class SuiteStarted(
            val id: String,
            val parentId: String?,
            val displayName: String,
            val location: Location?,
            /**
             * The TeamCity-formatted name, used solely as a workaround so TeamCity correctly categorizes tests.
             * Defaults to [displayName] for consumers that do not need TeamCity-specific categorization.
             */
            val teamCityName: String = displayName,
        ) : Event {
            companion object {
                const val TYPE = "suiteStarted"
            }
        }

        data class TestStarted(
            val id: String,
            val parentId: String?,
            val displayName: String,
            val location: Location?,
            /**
             * The TeamCity-formatted name, used solely as a workaround so TeamCity correctly categorizes tests.
             * Defaults to [displayName] for consumers that do not need TeamCity-specific categorization.
             */
            val teamCityName: String = displayName,
        ) : Event {
            companion object {
                const val TYPE = "testStarted"
            }
        }

        data class SuiteFinished(val id: String, val description: String?) : Event {
            companion object {
                const val TYPE = "suiteFinished"
            }
        }

        data class Succeeded(val id: String, val durationMillis: Long?) : Event {
            companion object {
                const val TYPE = "succeeded"
            }
        }

        data class Skipped(val id: String, val durationMillis: Long?, val description: String) : Event {
            companion object {
                const val TYPE = "skipped"
            }
        }

        data class Failed(
            val id: String,
            val durationMillis: Long?,
            val failureMessage: String,
            val stackTrace: String?,
            val expected: String?,
            val actual: String?,
            val expectedFilePath: Path?,
            val actualFilePath: Path?,
        ) : Event {
            companion object {
                const val TYPE = "failed"
            }
        }

        data class Report(
            val id: String,
            val key: String,
            val value: String,
            val mediaType: String?,
            val timestampMillis: Long?,
        ) : Event {
            companion object {
                const val TYPE = "report"
            }
        }
    }

    /**
     * Representation of JUnit's [org.junit.platform.engine.TestSource] variants.
     */
    sealed interface Location {
        data class Class(val className: String) : Location {
            companion object {
                const val TYPE = "class"
            }
        }

        data class Method(
            val className: String,
            val methodName: String,
            val methodParameterTypes: List<String>,
        ) : Location {
            companion object {
                const val TYPE = "method"
            }
        }

        data class Uri(val uri: String) : Location {
            companion object {
                const val TYPE = "uri"
            }
        }
    }

    private enum class Key(val wireName: String) {
        Id("id"), ParentId("parentId"), DisplayName("displayName"), LocationType("locationType"), Name("name"),
        ClassName("className"), MethodName("methodName"), MethodParameterTypes("methodParameterTypes"), Uri("uri"),
        Text("text"), Duration("duration"), Description("description"), Message("message"), StackTrace("stackTrace"),
        Expected("expected"), Actual("actual"), ExpectedFile("expectedFile"), ActualFile("actualFile"),
        Key("key"), Value("value"), MediaType("mediaType"), Timestamp("timestamp"),
    }

    fun encode(event: Event): String {
        val [type, fields] = when (event) {
            is Event.TestStdout -> Event.TestStdout.TYPE to fields(Key.Id to event.id, Key.Text to event.text)
            is Event.TestStderr -> Event.TestStderr.TYPE to fields(Key.Id to event.id, Key.Text to event.text)
            is Event.SuiteStarted -> Event.SuiteStarted.TYPE to fields(
                Key.Id to event.id, Key.ParentId to event.parentId, Key.DisplayName to event.displayName,
                Key.Name to event.teamCityName, *event.location.toFields().toTypedArray(),
            )
            is Event.TestStarted -> Event.TestStarted.TYPE to fields(
                Key.Id to event.id, Key.ParentId to event.parentId, Key.DisplayName to event.displayName,
                Key.Name to event.teamCityName, *event.location.toFields().toTypedArray(),
            )
            is Event.SuiteFinished -> Event.SuiteFinished.TYPE to fields(
                Key.Id to event.id,
                Key.Description to event.description
            )
            is Event.Succeeded -> Event.Succeeded.TYPE to fields(
                Key.Id to event.id,
                Key.Duration to event.durationMillis?.toString()
            )
            is Event.Skipped -> Event.Skipped.TYPE to fields(
                Key.Id to event.id,
                Key.Duration to event.durationMillis?.toString(),
                Key.Description to event.description
            )
            is Event.Failed -> Event.Failed.TYPE to fields(
                Key.Id to event.id,
                Key.Duration to event.durationMillis?.toString(),
                Key.Message to event.failureMessage,
                Key.StackTrace to event.stackTrace,
                Key.Expected to event.expected,
                Key.Actual to event.actual,
                Key.ExpectedFile to event.expectedFilePath?.pathString,
                Key.ActualFile to event.actualFilePath?.pathString,
            )
            is Event.Report -> Event.Report.TYPE to fields(
                Key.Id to event.id,
                Key.Key to event.key,
                Key.Value to event.value,
                Key.MediaType to event.mediaType,
                Key.Timestamp to event.timestampMillis?.toString()
            )
        }
        return buildString {
            append(PREFIX).append(type)
            fields.forEach { [key, value] ->
                append('|').append(key.wireName).append(':')
                    .append(value.escape())
            }
        }
    }

    fun isEvent(line: String): Boolean = line.startsWith(PREFIX)

    /**
     * Tries to deserialize the [line] into an [Event] if it [looks like one][isEvent].
     *
     * - If the line is malformed throws [IllegalArgumentException].
     * - If the line [doesn't look like an event][isEvent], returns `null` probably indicating that something in the process
     *   produces the output outside the JUnit listener lifetime.
     *
     * @see isEvent
     */
    fun decode(line: String): Event? {
        if (!isEvent(line)) return null

        val tokens = line.removePrefix(PREFIX).split('|')
        val type = requireNotNull(tokens.firstOrNull().takeUnless { it.isNullOrEmpty() }) {
            "Line '$line' doesn't have the type"
        }
        val fields = mutableMapOf<Key, String>()
        for (token in tokens.drop(1)) {
            val parts = token.split(':', limit = 2)
            require(parts.size == 2) {
                "Malformed field token '$token' in line '$line' (should have two parts delimited by ':')"
            }
            val [keyText, valueText] = parts
            val key = requireNotNull(Key.entries.singleOrNull { it.wireName == keyText }) {
                "Unknown key '$keyText' in the line '$line'"
            }
            val value = valueText.unescape()
            require(fields.put(key, value) == null) {
                "Duplicate field with key '$key' in line '$line'"
            }
        }
        return decodeEvent(type, fields)
    }

    private fun decodeEvent(tag: String, fields: Map<Key, String>): Event = when (tag) {
        Event.TestStdout.TYPE -> Event.TestStdout(id = fields[Key.Id], text = fields.required(Key.Text))
        Event.TestStderr.TYPE -> Event.TestStderr(id = fields[Key.Id], text = fields.required(Key.Text))
        Event.SuiteStarted.TYPE -> Event.SuiteStarted(
            id = fields.required(Key.Id),
            parentId = fields[Key.ParentId],
            displayName = fields.required(Key.DisplayName),
            location = fields.location(),
            teamCityName = fields.required(Key.Name),
        )
        Event.TestStarted.TYPE -> Event.TestStarted(
            id = fields.required(Key.Id),
            parentId = fields[Key.ParentId],
            displayName = fields.required(Key.DisplayName),
            location = fields.location(),
            teamCityName = fields.required(Key.Name),
        )
        Event.SuiteFinished.TYPE -> Event.SuiteFinished(
            id = fields.required(Key.Id),
            description = fields[Key.Description]
        )
        Event.Succeeded.TYPE -> Event.Succeeded(
            id = fields.required(Key.Id),
            durationMillis = fields.long(Key.Duration)
        )
        Event.Skipped.TYPE -> Event.Skipped(
            id = fields.required(Key.Id),
            durationMillis = fields.long(Key.Duration),
            description = fields.required(Key.Description)
        )
        Event.Failed.TYPE -> Event.Failed(
            id = fields.required(Key.Id),
            durationMillis = fields.long(Key.Duration),
            failureMessage = fields.required(Key.Message),
            stackTrace = fields[Key.StackTrace],
            expected = fields[Key.Expected],
            actual = fields[Key.Actual],
            expectedFilePath = fields[Key.ExpectedFile]?.let(Path::of),
            actualFilePath = fields[Key.ActualFile]?.let(Path::of),
        )
        Event.Report.TYPE -> Event.Report(
            id = fields.required(Key.Id),
            key = fields.required(Key.Key),
            value = fields.required(Key.Value),
            mediaType = fields[Key.MediaType],
            timestampMillis = fields.long(Key.Timestamp)
        )
        else -> throw IllegalArgumentException("Unknown JUnit protocol event tag '$tag'")
    }

    private fun Map<Key, String>.required(key: Key): String = requireNotNull(get(key)) {
        "Missing required key '$key'. Present fields: $keys"
    }

    private fun Map<Key, String>.long(key: Key): Long? {
        val value = this[key] ?: return null
        return requireNotNull(value.toLongOrNull()) { "Invalid numeric protocol field '$key': $value" }
    }

    private fun Location?.toFields(): List<Pair<Key, String?>> = when (this) {
        null -> []
        is Location.Class -> [Key.LocationType to Location.Class.TYPE, Key.ClassName to className]
        is Location.Method -> [
            Key.LocationType to Location.Method.TYPE,
            Key.ClassName to className,
            Key.MethodName to methodName,
            Key.MethodParameterTypes to methodParameterTypes.takeIf { it.isNotEmpty() }?.joinToString(","),
        ]
        is Location.Uri -> [Key.LocationType to Location.Uri.TYPE, Key.Uri to uri]
    }

    private fun Map<Key, String>.location(): Location? = when (val locationType = this[Key.LocationType]) {
        null -> null
        Location.Class.TYPE -> Location.Class(required(Key.ClassName))
        Location.Method.TYPE -> Location.Method(
            required(Key.ClassName),
            required(Key.MethodName),
            this[Key.MethodParameterTypes]?.split(',') ?: []
        )
        Location.Uri.TYPE -> Location.Uri(required(Key.Uri))
        else -> throw IllegalArgumentException("Unknown JUnit protocol location type '$locationType'")
    }

    private fun fields(vararg entries: Pair<Key, String?>): List<Pair<Key, String>> = entries.mapNotNull { entry ->
        entry.second?.let { entry.first to it }
    }

    private fun String.escape(): String = buildString {
        this@escape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                ':' -> append("\\:")
                '|' -> append("\\p")
                else -> append(char)
            }
        }
    }

    private fun String.unescape(): String = buildString {
        var index = 0
        while (index < this@unescape.length) {
            val char = this@unescape[index++]
            if (char != '\\') {
                append(char)
                continue
            }
            when (this@unescape.getOrNull(index++)) {
                '\\' -> append('\\')
                'r' -> append('\r')
                'n' -> append('\n')
                ':' -> append(':')
                'p' -> append('|')
                else -> throw IllegalArgumentException("Unknown escape sequence '\\$char'")
            }
        }
    }
}
