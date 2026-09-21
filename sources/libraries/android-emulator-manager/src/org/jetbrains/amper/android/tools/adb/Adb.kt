/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.tools.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

object Adb {

    internal suspend fun awaitDeviceChanged(predicate: (IDevice, Int) -> Boolean): IDevice =
        suspendCancellableCoroutine { continuation ->
            val listener = object : AndroidDebugBridge.IDeviceChangeListener {
                override fun deviceConnected(device: IDevice) = Unit
                override fun deviceDisconnected(device: IDevice?) = Unit
                override fun deviceChanged(device: IDevice, changeMask: Int) {
                    if (predicate(device, changeMask)) {
                        AndroidDebugBridge.removeDeviceChangeListener(this)
                        continuation.resume(device)
                    }
                }
            }
            AndroidDebugBridge.addDeviceChangeListener(listener)
            continuation.invokeOnCancellation {
                AndroidDebugBridge.removeDeviceChangeListener(listener)
            }
        }
}
