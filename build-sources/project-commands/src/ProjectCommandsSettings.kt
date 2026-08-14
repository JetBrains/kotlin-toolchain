/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.Configurable

/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@Configurable
interface ProjectCommandsSettings {
    val versions: Versions
}

@Configurable
interface Versions {
    /**
     * The version of Amper used to build Amper.
     */
    val bootstrapVersion: String

    /**
     * This is the version of the Zulu JRE that Amper wrappers use to run the Amper dist.
     */
    val amperJre: AmperJre

    /**
     * The minimum JUnit Platform version we should be compatible with.
     * This is used as a compile time dependency in our JUnit listeners.
     */
    val minSupportedJUnitPlatform: String

    /**
     * The user-visible default versions for toolchains and libs that are in our model.
     */
    val defaultsForUsers: DefaultVersions

}

@Configurable
interface AmperJre {
    val zuluDistro: String
    val java: String
}

@Configurable
interface DefaultVersions {
    val android: AndroidVersions
    val compose: String
    val composeHotReload: String
    val dataframe: String
    val jdk: Int
    val junitPlatform: String
    val kotlin: String
    val kotlinxRpc: String
    val kotlinxSerialization: String
    val ksp: String
    val ktor: String
    val lombok: String
    val springBoot: String
}

@Configurable
interface AndroidVersions {
    val buildTools: String
    val compileApiLevel: Int
    val minApiLevel: Int
}