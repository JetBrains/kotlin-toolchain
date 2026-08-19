/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

/**
 * A structured item of output produced by the Kotlin Toolchain.
 *
 * The interface itself is not sealed as events should be extendable and each subsystem can define its
 * own family of events.
 *
 * NB: All events should be Serializable to support passing events between processes for tools to be able to consume
 * events produced by the Kotlin Toolchain.
 */
sealed interface Event
