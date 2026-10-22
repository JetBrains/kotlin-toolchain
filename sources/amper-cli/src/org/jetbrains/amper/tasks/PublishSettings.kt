/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.appstoreconnect.AppStoreConnectApp
import org.jetbrains.amper.appstoreconnect.AppStoreConnectAuthKey
import org.jetbrains.amper.frontend.AmperModule
import java.nio.file.Path

class PublishSettings(val ios: IosPublishSettings? = null)

class IosPublishSettings(
    val module: AmperModule,
    val authKey: AppStoreConnectAuthKey,
    val app: AppStoreConnectApp,
    val exportOptionsPlistPath: Path,
)
