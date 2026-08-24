/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import iosUtils.IOSBaseTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.Ignore

class RuniOSExternalProjectsStandalone : IOSBaseTest() {

    @Test
    @Disabled("Unignore after https://jetbrains.team/p/amper/reviews/3356/timeline is merged")
    fun toDoListApp() = runIosAppTests(
        projectSource = amperExternalProject("todolistlite"),
        bundleIdentifier = "ios-app",
        iosAppModuleName = "ios-app",
    )

    @Test
    fun recipeApp() = runIosAppTests(
        projectSource = amperExternalProject("recipeapp"),
        bundleIdentifier = "ios-app",
        iosAppModuleName = "ios-app",
    )

    @Test
    @Disabled("Unignore after https://jetbrains.team/p/amper/reviews/3356/timeline is merged")
    fun swiftAppWithoutShared() = runIosAppTests(
        projectSource = amperExternalProject("swiftonlytodo"),
        bundleIdentifier = "swiftonlytodo",
    )

    @Test
    @Disabled("Temporarily disabled due to linking issues")
    fun kotlinConfApp() = runIosAppTests(
        projectSource = ProjectSource.RemoteRepository(
            cloneUrl = "https://github.com/JetBrains/kotlinconf-app.git",
            cloneIntoDirName = "kotlinconf-app",
            refLikeToCheckout = "kotlin-toolchain",
        ),
        bundleIdentifier = "com.kotlinconf.iosapp",
        iosAppModuleName = "iosApp",
    )
}
