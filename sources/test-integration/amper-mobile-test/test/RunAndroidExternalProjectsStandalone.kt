/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import androidUtils.AndroidBaseTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("android-emulator")
@Tag("external-test-project")
class RunAndroidExternalProjectsStandalone : AndroidBaseTest() {

    @Test
    fun kotlinConfApp() = runInstrumentedTests(
        projectSource = ProjectSource.RemoteRepository(
            cloneUrl = "https://github.com/JetBrains/kotlinconf-app.git",
            cloneIntoDirName = "kotlinconf-app",
            refLikeToCheckout = "kotlin-toolchain",
        ),
        applicationId = "com.jetbrains.kotlinconf",
        androidAppModuleName = "androidApp",
    )
}