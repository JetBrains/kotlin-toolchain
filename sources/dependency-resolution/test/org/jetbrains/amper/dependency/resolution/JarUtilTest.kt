/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.TestInfo
import kotlin.io.path.div
import kotlin.test.Test

class JarUtilTest : BaseDRTest() {

    /**
     * This test checks that the method [getNestedDirectories]
     * correctly returns subdirectories of a directory inside an archive.
     */
    @Test
    fun testGetNestedDirectories(testInfo: TestInfo) = runDrTest {
        doTest(
            testInfo,
            // multiplatform resolution
            platform = setOf(ResolutionPlatform.LINUX_X64, ResolutionPlatform.MACOS_X64),
            dependency = "org.jetbrains.kotlinx:atomicfu:0.32.1",
        )

        val kmpJar = Dirs.userCacheRoot /
                ".m2.cache" /
                "org" / "jetbrains" / "kotlinx" /
                "atomicfu" /
                "0.32.1" /
                "atomicfu-0.32.1.jar"

        val nativeMainCinterop = getNestedDirectories(kmpJar, "nativeMain-cinterop")
        kotlin.test.assertEquals(
            setOf("org.jetbrains.kotlinx_atomicfu-cinterop-interop"),
            nativeMainCinterop,
            "Unexpected nested directories of nativeMain-cinterop source set"
        )

        val rootDirectories = getNestedDirectories(kmpJar)
        kotlin.test.assertEquals(
            setOf("META-INF", "androidNativeMain", "androidNativeMain-cinterop", "commonMain", "concurrentMain",
                "jsAndWasmSharedMain", "linuxMain", "linuxMain-cinterop",
                "nativeMain", "nativeMain-cinterop", "nativeUnixLikeMain", "nativeUnixLikeMain-cinterop"
            ).sorted(),
            rootDirectories.sorted(),
            "Unexpected root directories of atomicfu KMP library"
        )
    }
}