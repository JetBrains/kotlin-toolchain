/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

// Valid common code for this module's platform set [jvm, linuxX64]: both platforms are covered by the 'nonWeb'
// fragment of the dependency, where 'Driver.open' is declared.
class MyDriver : Driver {
    override val hasConnectionPool: Boolean get() = false
    override fun open(fileName: String): String = fileName
}
