/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Returns files that are part of the Android package (whether APK or AAB) for the given [fragments] of the [module]
 * and [buildType]:
 *  - Android sources (assets, JNI libs, manifest, resources)
 *  - signing properties and ProGuard rules for [BuildType.Release], because they only affect release artifacts.
 */
internal fun androidFilesForPackaging(
    module: AmperModule,
    buildType: BuildType,
    fragments: List<Fragment>,
): List<Path> = buildSet {
    fragments.mapNotNull { it.sourceAndroidConventionPaths }.forEach { paths ->
        add(paths.assetsPath)
        add(paths.jniLibsPath)
        add(paths.manifestPath)
        add(paths.resourcesPath)
    }
    if (buildType == BuildType.Release) {
        fragments.forEach { add((module.source.moduleDir / it.settings.android.signing.propertiesFile).toAbsolutePath()) }
        add(module.source.moduleDir / "proguard-rules.pro")
    }
}.toList()
