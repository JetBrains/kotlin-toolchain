/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import androidUtils.AndroidBaseTest
import org.junit.jupiter.api.Test

class RunAndroidExternalProjectsStandalone : AndroidBaseTest() {

    @Test
    fun kmptexterAppTest() = runInstrumentedTests(
        projectSource = amperExternalProject("kmptxter"),
        applicationId = "com.river.kmptxter",
    )

    @Test
    fun toDoListApp() = runInstrumentedTests(
        projectSource = amperExternalProject("todolistlite"),
        applicationId = "org.jetbrains.todo",
        androidAppModuleName = "android-app",
    )

    @Test
    fun recipeApp() = runInstrumentedTests(
        projectSource = amperExternalProject("recipeapp"),
        applicationId = "com.recipeapp",
        androidAppModuleName = "android-app",
    )

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