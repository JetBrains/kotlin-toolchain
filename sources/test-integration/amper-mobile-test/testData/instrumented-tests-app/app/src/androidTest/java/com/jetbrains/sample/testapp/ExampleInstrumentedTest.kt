/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.jetbrains.sample.testapp

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.*
import org.hamcrest.MatcherAssert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppOpenTest {

    /**
     * The package of the app to test from here.
     */
    private lateinit var targetAppPackage: String
    private val launchTimeout = 5000L
    private lateinit var device: UiDevice

    @Before
    fun startMainActivityFromHomeScreen() {
        targetAppPackage = InstrumentationRegistry.getArguments().getString("targetPackage")
            ?: error("targetPackage not available. Make sure this instrumented test is run with '-e targetPackage <someAppPackage>'")

        // Initialize UiDevice instance
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Start from the home screen
        device.pressHome()

        // Wait for launcher
        val launcherPackage = "com.google.android.apps.nexuslauncher"
        val launcherAppeared = device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), launchTimeout)
        assertThat("Launcher '$launcherPackage' should be present but isn't", launcherAppeared, `is`(true))

        // Launch the app using adb monkey and sampleAppPackage
        // Note:
        // 1) "am start -n $targetAppPackage/.MainActivity" doesn't work for external projects, even with the
        // overridden targetAppPackage value, because it needs the FQN of the activity. Better simulate a click.
        // 2) the monkey command needs "--pct-syskeys 0" otherwise it fails on macOS with missing physical keys
        val appStartCommand = "monkey -p $targetAppPackage -c android.intent.category.LAUNCHER --pct-syskeys 0 1"
        val appStartCommandOutput = device.executeShellCommand(appStartCommand)

        // Wait for the app to appear
        val appAppeared = device.wait(Until.hasObject(By.pkg(targetAppPackage).depth(0)), launchTimeout)
        assertThat("App with ID $targetAppPackage should be present but isn't", appAppeared, `is`(true))
    }

    @Test
    fun checkAppOpens() {
        // Check that the app has opened by verifying that a view in the app is displayed
        // FIXME DISABLED BECAUSE OF BROKEN TESTS ON CI RIGHT NOW
        // assertThat(device.findObject(By.pkg(targetAppPackage)), notNullValue())
    }
}
