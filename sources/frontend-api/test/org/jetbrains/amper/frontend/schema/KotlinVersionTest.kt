/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinVersionTest {

    @Test
    fun `supportsGroupQualifiedJvmModuleName is false before Kotlin 2_4_0-Beta2`() {
        assertFalse(KotlinVersion("2.2.20").supportsGroupQualifiedJvmModuleName())
        assertFalse(KotlinVersion("2.3.0").supportsGroupQualifiedJvmModuleName())
        assertFalse(KotlinVersion("2.3.20-RC1").supportsGroupQualifiedJvmModuleName())
        // the KT-82216 sanitization fix is not in 2.4.0-Beta1 yet
        assertFalse(KotlinVersion("2.4.0-Beta1").supportsGroupQualifiedJvmModuleName())
    }

    @Test
    fun `supportsGroupQualifiedJvmModuleName is true since Kotlin 2_4_0-Beta2`() {
        // 2.4.0-Beta2 is the first build containing the KT-82216 sanitization fix
        assertTrue(KotlinVersion("2.4.0-Beta2").supportsGroupQualifiedJvmModuleName())
        assertTrue(KotlinVersion("2.4.0-RC").supportsGroupQualifiedJvmModuleName())
        assertTrue(KotlinVersion("2.4.0").supportsGroupQualifiedJvmModuleName())
        assertTrue(KotlinVersion("2.4.20").supportsGroupQualifiedJvmModuleName())
    }

    @Test
    fun `supportsGroupQualifiedJvmModuleName compares the minor version numerically`() {
        // a string comparison would consider "10" < "4"
        assertTrue(KotlinVersion("2.10.0").supportsGroupQualifiedJvmModuleName())
    }
}
