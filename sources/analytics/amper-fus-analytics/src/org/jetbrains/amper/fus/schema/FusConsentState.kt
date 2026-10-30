/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * The state of the user's consent for FUS data collection, as reported in FUS.
 */
enum class FusConsentState(override val fusId: String) : FusEnum<FusConsentState> {
    Accepted("accepted"),
    Rejected("rejected"),
    NotAsked("not_asked"),
}

/**
 * The OS family of the current host machine.
 *
 * It is different from [OsFamily][org.jetbrains.amper.system.info.OsFamily] because it avoids distinguishing niche
 * OSes that might narrow down the number of users too much in the corresponding bucket.
 */
enum class AnonymousOsFamily(override val fusId: String) : FusEnum<FusConsentState> {
    Windows("windows"),
    MacOS("macos"),
    UnixLike("unix_like"),
}
