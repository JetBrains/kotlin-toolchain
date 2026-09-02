/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import androidUtils.AndroidBaseTest
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.io.path.div

@Tag("android-emulator")
class AndroidInstrumentedAppTest : AndroidBaseTest() {

    private val androidTestProjectsPath = Dirs.amperTestProjectsRoot / "android"

    @Test
    fun simple() = runInstrumentedTests(
        projectSource = ProjectSource.Local(androidTestProjectsPath / "simple"),
    )

    @Test
    fun appcompat() = runInstrumentedTests(
        projectSource = ProjectSource.Local(androidTestProjectsPath / "appcompat"),
    )

    @Test
    fun parcelize() = runInstrumentedTests(
        projectSource = ProjectSource.Local(androidTestProjectsPath / "parcelize"),
    )

    @Test
    fun kmptexterAppTest() = runInstrumentedTests(
        projectSource = testProject("kmptxter"),
        applicationId = "com.river.kmptxter",
    )

    @Test
    fun toDoListApp() = runInstrumentedTests(
        projectSource = testProject("todolistlite"),
        applicationId = "org.jetbrains.todo",
        androidAppModuleName = "android-app",
    )

    @Test
    fun recipeApp() = runInstrumentedTests(
        projectSource = testProject("recipeapp"),
        applicationId = "com.recipeapp",
        androidAppModuleName = "android-app",
    )
}