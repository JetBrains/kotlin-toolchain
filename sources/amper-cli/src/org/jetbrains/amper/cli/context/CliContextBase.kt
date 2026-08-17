/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.context

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import org.jetbrains.amper.android.AndroidSdkDetector
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import kotlin.io.path.createDirectories

sealed class CliContextBase : CliContext {
    override val openTelemetry: OpenTelemetry by lazy {
        // by the time we get here, GlobalOpenTelemetry should be set
        GlobalOpenTelemetry.get()
    }

    override val androidHomeRoot: AndroidHomeRoot by lazy {
        AndroidHomeRoot(AndroidSdkDetector.detectSdkPath().createDirectories())
    }

    override val androidSdkProvider: AndroidSdkProvider by lazy {
        AndroidSdkProvider(
            userCacheRoot = userCacheRoot,
            sdkRoot = androidHomeRoot.path,
            incrementalCache = incrementalCache,
            openTelemetry = openTelemetry,
        )
    }

    override val jdkProvider: JdkProvider by lazy {
        JdkProvider(
            userCacheRoot = userCacheRoot,
            openTelemetry = openTelemetry,
            incrementalCache = incrementalCache,
        )
    }
}
