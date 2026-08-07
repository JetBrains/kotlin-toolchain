/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

/**
 * Neither of the common declarations has the method that the corresponding refinement adds:
 * [Opener.open] only exists in 'nonWeb', and [Closer.close] only exists in 'nonJvm'.
 *
 * The 'native' fragment refines both of them, and a depth-first walk of that diamond yields 'common' in the middle,
 * before one of the two refinements. Which of the two ends up after 'common' depends on the order in which the
 * aliases happen to be declared, so both refinements add a method here: whichever one loses, the compilation of
 * MyDriver fails.
 */
expect interface Opener {
    val hasConnectionPool: Boolean
}

expect interface Closer {
    val isClosed: Boolean
}
