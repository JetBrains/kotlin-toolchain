/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.jetbrains.cidr.xcode.model

import kotlin.io.path.writeText

fun PBXProjectFile.saveProperly() {
    // Need to truncate the file before saving,
    // as it doesn't overwrite the whole file and may leave some old text at the end of the file
    pbxProjFile.writeText("")
    save(true)
    // project.save resaves the project with a 0 mtime and Xcode ignores changes to the package list as a result
    pbxProjFile.toFile().setLastModified(System.currentTimeMillis())
}