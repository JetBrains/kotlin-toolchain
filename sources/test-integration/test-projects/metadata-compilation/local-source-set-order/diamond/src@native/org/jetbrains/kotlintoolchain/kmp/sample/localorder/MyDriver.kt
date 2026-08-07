/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

// The 'native' fragment refines both 'nonWeb' and 'nonJvm', so it must resolve 'Opener' to the 'nonWeb' declaration
// and 'Closer' to the 'nonJvm' one, rather than to the memberless 'expect' declarations from 'common'.
class MyDriver : Opener, Closer {
    override val hasConnectionPool: Boolean get() = false
    override val isClosed: Boolean get() = true
    override fun open(fileName: String): String = fileName
    override fun close(fileName: String): String = fileName
}
