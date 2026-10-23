/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

import com.jetbrains.fus.reporting.schema.generation.EventDescriptor
import com.jetbrains.fus.reporting.schema.generation.EventsScheme
import com.jetbrains.fus.reporting.schema.generation.FieldDescriptor
import com.jetbrains.fus.reporting.schema.generation.GroupDescriptor
import com.jetbrains.fus.reporting.schema.generation.PluginSchemeDescriptor
import com.jetbrains.fus.reporting.schema.generation.RecorderDescriptor
import com.jetbrains.fus.reporting.schema.generation.RecorderFieldDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val schemeJson = Json {
    prettyPrint = true
    explicitNulls = false
}

internal fun EventsScheme.toJsonString(): String = schemeJson.encodeToString(toSurrogate())

/**
 * Serializable counterpart of [EventsScheme], because the FUS library's schema DTOs are not `@Serializable`.
 */
@Serializable
private data class EventsSchemeSurrogate(
    val buildNumber: String?,
    val commitHash: String?,
    val recorderScheme: List<RecorderDescriptorSurrogate>?,
    val scheme: List<GroupDescriptorSurrogate>,
)

@Serializable
private data class GroupDescriptorSurrogate(
    val id: String,
    val type: String,
    val version: Int,
    val description: String,
    val className: String?,
    val fileName: String?,
    val recorder: String?,
    val schema: List<EventDescriptorSurrogate>,
    val plugin: PluginSchemeDescriptorSurrogate?,
)

@Serializable
private data class PluginSchemeDescriptorSurrogate(val id: String)

@Serializable
private data class EventDescriptorSurrogate(
    val event: String,
    val description: String,
    val category: String?,
    @SerialName("presentation_template")
    val presentationTemplate: String?,
    val fields: List<FieldDescriptorSurrogate>,
)

@Serializable
private data class FieldDescriptorSurrogate(
    val path: String,
    val description: String?,
    val dataType: String,
    val shouldBeAnonymized: Boolean,
    val value: List<String>,
)

@Serializable
private data class RecorderDescriptorSurrogate(
    val recorderId: String,
    val recorderVersion: Int,
    val ids: List<RecorderFieldDescriptorSurrogate>,
    val clientData: List<RecorderFieldDescriptorSurrogate>,
    val systemData: List<RecorderFieldDescriptorSurrogate>,
)

@Serializable
private data class RecorderFieldDescriptorSurrogate(
    val path: String,
    val required: Boolean,
    val values: List<String>?,
)

private fun EventsScheme.toSurrogate() = EventsSchemeSurrogate(
    buildNumber = buildNumber,
    scheme = scheme.map { it.toSurrogate() }.sortedBy { it.id },
    commitHash = commitHash,
    recorderScheme = recorderScheme?.map { it.toSurrogate() }?.sortedBy { it.recorderId },
)

private fun GroupDescriptor.toSurrogate() = GroupDescriptorSurrogate(
    id = id,
    type = type.name,
    version = version,
    schema = schema.map { it.toSurrogate() }.sortedBy { it.event },
    description = description,
    className = className,
    fileName = fileName,
    recorder = recorder,
    plugin = plugin?.toSurrogate(),
)

private fun PluginSchemeDescriptor.toSurrogate() = PluginSchemeDescriptorSurrogate(id = id)

private fun EventDescriptor.toSurrogate() = EventDescriptorSurrogate(
    event = event,
    fields = fields.map { it.toSurrogate() }.sortedBy { it.path },
    description = description,
    category = category?.name,
    presentationTemplate = presentation_template,
)

private fun FieldDescriptor.toSurrogate() = FieldDescriptorSurrogate(
    path = path,
    value = value.toList().sorted(),
    dataType = dataType.name,
    shouldBeAnonymized = shouldBeAnonymized,
    description = description,
)

private fun RecorderDescriptor.toSurrogate() = RecorderDescriptorSurrogate(
    recorderId = recorderId,
    recorderVersion = recorderVersion,
    ids = ids.map { it.toSurrogate() },
    clientData = clientData.map { it.toSurrogate() }.sortedBy { it.path },
    systemData = systemData.map { it.toSurrogate() }.sortedBy { it.path },
)

private fun RecorderFieldDescriptor.toSurrogate() = RecorderFieldDescriptorSurrogate(
    path = path,
    required = required,
    values = values?.toList()?.sorted(),
)
