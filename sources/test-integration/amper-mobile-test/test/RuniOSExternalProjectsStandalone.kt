/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import iosUtils.IOSBaseTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("ios-simulator")
class RuniOSExternalProjectsStandalone : IOSBaseTest() {

    @Test
    fun toDoListApp() = runIosAppTests(
        projectSource = testProject("todolistlite"),
        bundleIdentifier = "ios-app",
        iosAppModuleName = "ios-app",
    )

    @Test
    fun recipeApp() = runIosAppTests(
        projectSource = testProject("recipeapp"),
        bundleIdentifier = "ios-app",
        iosAppModuleName = "ios-app",
    )

    @Test
    fun swiftAppWithoutShared() = runIosAppTests(
        projectSource = testProject("swiftonlytodo"),
        bundleIdentifier = "swiftonlytodo",
    )

    @Tag("external-test-project")
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
