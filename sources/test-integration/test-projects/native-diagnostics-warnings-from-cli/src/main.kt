/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@Deprecated("Use newFun() instead.")
fun oldFun() = Unit

fun newFun() = Unit

fun useDeprecated() {
    oldFun()
}

// The range of this warning spans multiple lines
fun unusedExpression() {
    """
        multiline
    """
}
