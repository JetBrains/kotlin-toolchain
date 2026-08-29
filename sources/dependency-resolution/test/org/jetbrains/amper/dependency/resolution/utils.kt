/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.junit.jupiter.api.TestInfo

fun TestInfo.nameToDependency(): String = testMethod.get().name.replace('_', '.').replace(' ', ':')

fun TestInfo.nameToGoldenFile(): String = testMethod.get().name.replace("_", ".").replace(" ", "_")
