/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.android.AndroidBuildRequest
import org.jetbrains.amper.android.AndroidModuleData
import org.jetbrains.amper.android.ResolvedDependency
import org.jetbrains.amper.android.runAndroidBuild
import org.jetbrains.amper.cli.context.AmperBuildLogsRoot
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.lazyload.ExtraClasspath
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.MavenLocalRepository
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.api.toStableJsonLikeString
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.processes.GradleDaemonShutdownHook
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.jvm.JvmRuntimeClasspathTask
import org.jetbrains.amper.util.BuildType
import org.jetbrains.amper.util.toAndroidRequestBuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.pathString

abstract class AndroidDelegatedGradleTask(
    private val module: AmperModule,
    private val buildType: BuildType,
    private val incrementalCache: IncrementalCache,
    private val androidSdkPath: Path,
    private val fragments: List<Fragment>,
    private val projectRoot: AmperProjectRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val taskOutputPath: TaskOutputRoot,
    private val buildLogsRoot: AmperBuildLogsRoot,
    private val jdkProvider: JdkProvider,
    override val taskName: TaskName,
) : Task {

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val runtimeClasspath = runtimeClasspath(dependenciesResult)

        val moduleGradlePath = module.gradlePath(projectRoot)
        val androidModuleData = AndroidModuleData(
            modulePath = moduleGradlePath,
            moduleClasses = emptyList(),
            resolvedAndroidRuntimeDependencies = runtimeClasspath.map {
                ResolvedDependency(path = it, id = it.dependencyId())
            },
        )
        val request = AndroidBuildRequest(
            root = projectRoot.path,
            phase = phase,
            modules = setOf(androidModuleData),
            buildTypes = setOf(buildType.toAndroidRequestBuildType),
            sdkDir = androidSdkPath,
            targets = setOf(moduleGradlePath),
        )

        val googleServicesFileName = "google-services.json"
        val googleServicesJson = module.source.moduleDir.let { moduleDir ->
            val servicesJsonPath = moduleDir / googleServicesFileName
            if (servicesJsonPath.exists()) servicesJsonPath else null
        }

        val jdk = jdkProvider.getJdkOrUserError(module.jdkSettings)

        val executionResult = incrementalCache.execute(
            key = taskName.id.value,
            inputValues = mapOf(
                "jdk.version" to jdk.version,
                "jdk.home" to jdk.homeDir.pathString,
                "androidConfig" to fragments.joinToString { it.settings.android.toStableJsonLikeString() },
            ),
            inputFiles = runtimeClasspath + additionalInputFiles + listOfNotNull(googleServicesJson),
        ) {
            val gradleProjectPath = (taskOutputPath.path / "gradle-project").also { path -> path.createDirectories() }
            googleServicesJson?.let {
                logger.debug("Using google services json at {}", it)
                googleServicesJson.copyTo(gradleProjectPath / googleServicesFileName, overwrite = true)
            }
            logger.debug("Using android sdk at {}", androidSdkPath)
            val logFileName = UUID.randomUUID()
            val gradleLogStdoutPath =
                buildLogsRoot.path / "gradle" / "${this::class.simpleName}-$logFileName.stdout"
            val gradleLogStderrPath =
                buildLogsRoot.path / "gradle" / "${this::class.simpleName}-$logFileName.stderr"
            gradleLogStdoutPath.createParentDirectories()
            GradleDaemonShutdownHook.setupIfNeeded()
            val result = runAndroidBuild(
                buildRequest = request,
                buildPath = gradleProjectPath,
                gradleLogStdoutPath = gradleLogStdoutPath,
                gradleLogStderrPath = gradleLogStderrPath,
                jdkDir = jdk.homeDir,
                gradlePluginJars = ExtraClasspath.ANDROID_INTEGRATION_GRADLE_PLUGIN.findJarsInDistribution(),
                eventHandler = { it.handle(gradleLogStdoutPath, gradleLogStderrPath) },
            )
            IncrementalCache.ExecutionResult(result.filter(::outputFilterPredicate))
        }
        taskOutputPath.path.createDirectories()
        executionResult.outputFiles.forEach {
            it.copyToRecursively(
                taskOutputPath.path.resolve(it.fileName), followLinks = false, overwrite = true
            )
        }
        return result(executionResult.outputFiles)
    }

    protected abstract val phase: AndroidBuildRequest.Phase

    protected open val additionalInputFiles: List<Path> = emptyList()
    protected open fun outputFilterPredicate(path: Path): Boolean = true
    protected open fun result(artifacts: List<Path>): TaskResult = Result(artifacts)
    protected open fun runtimeClasspath(dependenciesResult: List<TaskResult>): List<Path> {
        val runtimeClasspathTaskResult =
            dependenciesResult.filterIsInstance<JvmRuntimeClasspathTask.Result>().singleOrNull()
                ?: error("${JvmRuntimeClasspathTask::class.simpleName} result is not found in dependencies of $taskName")
        val runtimeClasspath = runtimeClasspathTaskResult.jvmRuntimeClasspath
        return runtimeClasspath
    }

    /**
     * The identity to hand this classpath element over to Gradle with (see [ResolvedDependency.id]): its path
     * relative to the storage it was resolved into.
     *
     * Elements that are not located in any of those storages (locally compiled classes, generated classes, the
     * Android platform jar ...) are identified by their absolute path. It is machine-specific, but still unique,
     * which is all that Gradle needs.
     *
     * Relativization is done to make diagnostics reported by Gradle nicer, it covers all cases for now,
     * but even if id does not, there is no harm, uniqueness is the key here.
     *
     * An alternative solution would be propagating maven coordinates here along with paths, but
     * that requires changing of [org.jetbrains.amper.tasks.ResolveExternalDependenciesTask.Result] that might be an
     * overcomplication for solving this particular case.
     */
    private fun Path.dependencyId(): String {
        val absolutePath = toAbsolutePath().normalize()
        val storage = storageRoots.firstOrNull { absolutePath.startsWith(it) }
        return (storage?.relativize(absolutePath) ?: absolutePath).invariantSeparatorsPathString
    }

    /**
     * The storages a resolved dependency can be located in:
     *  - the Amper cache, which contains Amper's own local storage,
     *  - the local Maven repository, in case the dependency was taken from `mavenLocal`.
     *
     * Lazy because discovering the local Maven repository reads the Maven settings from disk.
     */
    private val storageRoots: List<Path> by lazy {
        listOf(userCacheRoot.path, MavenLocalRepository.Default.repository).map { it.toAbsolutePath().normalize() }
    }

    class Result(val artifacts: List<Path>) : TaskResult

    private val logger = LoggerFactory.getLogger(this::class.java)
}
