/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

import org.jetbrains.amper.buildinfo.AmperBuild
import kotlin.test.Test
import kotlin.test.assertTrue

class KtcVersionTest {

    @Test
    fun currentVersionIsPresent() {
        val currentVersion = AmperBuild.mavenVersion
        val currentVersionForFus = currentVersion.trimKtcVersionForFus()
        assertTrue(
            KnownKtcVersions.contains(currentVersionForFus),
            "The known KTC versions for FUS should contain the current version ($currentVersion -> $currentVersionForFus)",
        )
    }
}
