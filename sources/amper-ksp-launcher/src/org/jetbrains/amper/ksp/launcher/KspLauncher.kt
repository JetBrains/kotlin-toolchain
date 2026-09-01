/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
@file:JvmName("KspLauncher")

package org.jetbrains.amper.ksp.launcher

import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val kspMainClass = requireNotNull(args.firstOrNull()) { "Missing KSP main class" }
    val kspArgs = args.drop(1).toTypedArray()
    val exitCode = try {
        Class.forName(kspMainClass).getMethod("main", Array<String>::class.java).invoke(null, kspArgs)
        0
    } catch (e: InvocationTargetException) {
        // We explicitly catch internal processor errors here to work around https://github.com/google/ksp/issues/3120
        e.targetException.printStackTrace()
        1
    }
    exitProcess(exitCode)
}
