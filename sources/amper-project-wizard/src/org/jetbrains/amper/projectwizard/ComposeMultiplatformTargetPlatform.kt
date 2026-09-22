/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import org.jetbrains.amper.templates.AndroidAppModule
import org.jetbrains.amper.templates.AppDir
import org.jetbrains.amper.templates.DesktopAppModule
import org.jetbrains.amper.templates.IosAppModule
import org.jetbrains.amper.templates.ServerModule
import org.jetbrains.amper.templates.WebAppModule

/**
 * A target platform for the parameterized Compose Multiplatform application flow of `kotlin new`/`kotlin init`.
 *
 * The `--target-platform` CLI values intentionally match the vocabulary of the KMP web wizard
 * (https://kmp.jetbrains.com/), even though they don't always match the Kotlin Toolchain's own platform/product
 * names: the wizard calls the JVM desktop app `desktop`, and the Wasm/JS app `web`.
 *
 * These are the five targets offered by the wizard: the shared-UI Compose Multiplatform targets (`android`, `ios`,
 * `desktop`, and `web`) and an optional Ktor `server` backend. The server doesn't contribute a platform or source
 * set to the Compose `shared` module; it uses a separate `core` module for code shared with the client apps.
 */
enum class ComposeMultiplatformTargetPlatform(
    /** The value used for this target on the `--target-platform` CLI option. */
    val cliValue: String,
    /** A short human-readable name of this target, e.g. for the interactive multi-select prompt. */
    val displayName: String,
    /** The module directory generated for this target in the `compose-multiplatform` template. */
    val moduleDir: String,
    /** The Amper platforms that this target contributes to the shared module's `platforms:` list. */
    val sharedModulePlatforms: List<String>,
    /**
     * The `@platform` source-set qualifier used by this target inside the shared module (e.g. `src@android`,
     * `test@jvm`). Used to prune the shared module's platform-specific source sets down to the selected targets.
     * This is `null` for targets, such as the server, that don't use the Compose `shared` module.
     */
    val sharedModuleSourceSetQualifier: String?,
) {
    ANDROID(
        cliValue = "android",
        displayName = "Android",
        moduleDir = "$AppDir$AndroidAppModule",
        sharedModulePlatforms = ["android"],
        sharedModuleSourceSetQualifier = "android",
    ),
    IOS(
        cliValue = "ios",
        displayName = "iOS",
        moduleDir = "$AppDir$IosAppModule",
        sharedModulePlatforms = ["iosArm64", "iosSimulatorArm64"],
        sharedModuleSourceSetQualifier = "ios",
    ),
    DESKTOP(
        cliValue = "desktop",
        displayName = "Desktop (JVM)",
        moduleDir = "$AppDir$DesktopAppModule",
        sharedModulePlatforms = ["jvm"],
        sharedModuleSourceSetQualifier = "jvm",
    ),
    WEB(
        cliValue = "web",
        displayName = "Web (Wasm)",
        moduleDir = "$AppDir$WebAppModule",
        sharedModulePlatforms = ["wasmJs"],
        sharedModuleSourceSetQualifier = "wasmJs",
    ),
    SERVER(
        cliValue = "server",
        displayName = "Server (Ktor)",
        moduleDir = ServerModule,
        sharedModulePlatforms = [],
        sharedModuleSourceSetQualifier = null,
    );

    /** The platforms this target contributes to the `core` module when a server backend is selected. */
    val coreModulePlatforms: List<String>
        get() = if (this == SERVER) ["jvm"] else sharedModulePlatforms

    companion object {
        /** All valid `--target-platform` values, mapped to their [ComposeMultiplatformTargetPlatform]. */
        val byCliValue: Map<String, ComposeMultiplatformTargetPlatform> = entries.associateBy { it.cliValue }
    }
}

/** Targets selected by default when the interactive wizard's target list is first displayed. */
val DefaultInteractiveComposeMultiplatformTargets: Set<ComposeMultiplatformTargetPlatform> = [
    ComposeMultiplatformTargetPlatform.ANDROID,
    ComposeMultiplatformTargetPlatform.IOS,
    ComposeMultiplatformTargetPlatform.DESKTOP,
    ComposeMultiplatformTargetPlatform.WEB,
]
