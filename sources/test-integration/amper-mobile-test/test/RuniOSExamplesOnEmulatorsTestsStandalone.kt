/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.io.path.div

@Tag("ios-simulator")
@ProcessLeak
class RuniOSExamplesOnEmulatorsTestsStandalone : swiftpm.SwiftPMImportTests() {

    @Test
    fun composeiOSAppStandalone() = runIosAppTests(
        projectSource = ProjectSource.Local(Dirs.examplesRoot / "compose-ios"),
        bundleIdentifier = "compose-ios",
    )

    @Test
    fun composeiOSAppMultiplatform() = runIosAppTests(
        projectSource = ProjectSource.Local(Dirs.examplesRoot / "compose-multiplatform"),
        bundleIdentifier = "ios-app",
        iosAppModuleName = "ios-app",
    )
}
