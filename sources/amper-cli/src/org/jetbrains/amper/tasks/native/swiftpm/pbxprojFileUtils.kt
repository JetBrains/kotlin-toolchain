/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("PackageDirectoryMismatch")
// Exposes package private
package com.jetbrains.cidr.xcode.model

fun PBXProjectFile.addObject(entity: PBXObject) = addObject(entity, null)