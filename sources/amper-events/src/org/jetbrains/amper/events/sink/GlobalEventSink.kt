/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.GlobalScopedEvent

/**
 * Allows consuming [GlobalScopedEvent]s.
 * Not an interface to support proper contravariance.
 */
typealias GlobalEventSink = EventSink<GlobalScopedEvent>
