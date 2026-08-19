/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@JvmInline @Serializable
value class BuildId(val value: Uuid = Uuid.random())

@JvmInline @Serializable
value class TaskExecutionId(val value: Uuid = Uuid.random())

@JvmInline @Serializable
value class OperationId(val value: Uuid = Uuid.random())
