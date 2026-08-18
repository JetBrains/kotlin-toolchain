/**
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import org.jetbrains.kotlintoolchain.kmp.sample.platformSpecificElement

fun main(args: Array<String>) {
    println(platformSpecificElement)

    // tinylog classes come from the exported compilation dependency of the kmpSinglePlatform library
    println(org.tinylog.Logger.isTraceEnabled())
}