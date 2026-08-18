/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

@RequiresOptIn(
    message = "Direct access to default versions is discouraged. These default versions should only be used in the " +
            "frontend's settings as default values for configurable versions. Everywhere else, use the versions from " +
            "those settings.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class DiscouragedDirectDefaultVersionAccess

/**
 * Default versions used in settings. These values are automatically updated via `do syncVersions`.
 * The /*managed_default*/ markers are used to find the version.
 *
 * **IMPORTANT** If you add a new version here, also add configure the `syncVersions` command in `project-commands`
 * accordingly.
 */
@DiscouragedDirectDefaultVersionAccess
object DefaultVersions {
    /*managed_default*/ val androidBuildTools = "37.0.0"
    /*managed_default*/ val androidCompileApiLevel = 37
    /*managed_default*/ val androidMinApiLevel = 24
    /*managed_default*/ val compose = "1.11.1"
    /*managed_default*/ val composeHotReload = "1.2.0"
    /*managed_default*/ val dataframe = "1.0.0-rc01"
    /*managed_default*/ val jdk = 25
    /*managed_default*/ val junitPlatform = "6.1.3"
    /*managed_default*/ val kotlin = "2.4.10"
    /*managed_default*/ val kotlinxRpc = "0.10.3"
    /*managed_default*/ val kotlinxSerialization = "1.11.0"
    /*managed_default*/ val ksp = "2.3.11"
    /*managed_default*/ val ktor = "3.5.2"
    /*managed_default*/ val lombok = "1.18.46"
    /*managed_default*/ val springBoot = "4.1.0"
}
