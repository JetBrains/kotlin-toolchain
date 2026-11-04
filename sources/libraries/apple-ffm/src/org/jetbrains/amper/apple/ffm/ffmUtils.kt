/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.apple.ffm

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

private val LINKER: Linker = Linker.nativeLinker()

internal fun SymbolLookup.downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
    LINKER.downcallHandle(findOrThrow(name), descriptor)

