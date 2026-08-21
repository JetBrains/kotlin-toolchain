/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.cli.context.AmperBuildLogsRoot
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.div

abstract class LastPhaseAndroidDelegatedGradleTask(
    private val module: AmperModule,
    buildType: BuildType,
    incrementalCache: IncrementalCache,
    androidSdkPath: Path,
    fragments: List<Fragment>,
    projectRoot: AmperProjectRoot,
    userCacheRoot: AmperUserCacheRoot,
    taskOutputPath: TaskOutputRoot,
    buildLogsRoot: AmperBuildLogsRoot,
    jdkProvider: JdkProvider,
    override val taskName: TaskName,
) : AndroidDelegatedGradleTask(
    module,
    buildType,
    incrementalCache,
    androidSdkPath,
    fragments,
    projectRoot,
    userCacheRoot,
    taskOutputPath,
    buildLogsRoot,
    jdkProvider,
    taskName,
) {

    override val additionalInputFiles: List<Path> = if (buildType == BuildType.Release) {
        buildList {
            fragments.forEach { fragment ->
                fragment.sourceAndroidConventionPaths?.let { paths ->
                    add(paths.assetsPath)
                    add(paths.jniLibsPath)
                    add(paths.manifestPath)
                    add(paths.resourcesPath)
                }
                module.source.moduleDir.let { moduleDir ->
                    add((moduleDir / fragment.settings.android.signing.propertiesFile).toAbsolutePath())
                    add(moduleDir / "proguard-rules.pro")
                }
            }
        }.distinct()
    } else emptyList()
}
