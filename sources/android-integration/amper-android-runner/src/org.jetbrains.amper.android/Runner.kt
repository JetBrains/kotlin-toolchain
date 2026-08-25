/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android

import com.android.builder.model.v2.models.AndroidProject
import kotlinx.serialization.json.Json
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.model.GradleProject
import java.io.BufferedOutputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.notExists
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

private const val DEBUG_JVM_AGENT = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"

/**
 * Overrides the Gradle distribution the Tooling API downloads for Android builds.
 * Point it at a mirror (e.g. https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-8.14.3-bin.zip)
 * when services.gradle.org is unreachable. When unset, Gradle uses its default distribution URL.
 */
private const val GRADLE_DISTRIBUTION_URL_ENV = "KOTLIN_TOOLCHAIN_GRADLE_DISTRIBUTION_URL"

private fun <T : ConfigurableLauncher<T>> T.addDebugJvmArgumentsIf(debug: Boolean): T =
    if (debug) addJvmArguments(DEBUG_JVM_AGENT) else this

/**
 * Forwards the current process environment to the delegated Gradle build (adding `ANDROID_HOME` when the SDK
 * directory is known). Without this, the Gradle daemon doesn't inherit variables such as
 * `KOTLIN_DEFAULT_MAVEN_CENTRAL_URL`, so dependency resolution falls back to the public Maven Central / Gradle
 * Plugin Portal instead of the JetBrains cache-redirector, which rate-limits (HTTP 429) on CI agents.
 */
private fun <T : ConfigurableLauncher<T>> T.forwardEnvironment(buildRequest: AndroidBuildRequest): T {
    val androidHome = buildRequest.sdkDir?.let { mapOf("ANDROID_HOME" to it.toAbsolutePath().toString()) } ?: emptyMap()
    setEnvironmentVariables(System.getenv() + androidHome)
    return this
}

fun runAndroidBuild(
    buildRequest: AndroidBuildRequest,
    buildPath: Path,
    gradleLogStdoutPath: Path,
    gradleLogStderrPath: Path,
    jdkDir: Path,
    gradlePluginJars: List<Path>,
    debug: Boolean = false,
    eventHandler: (ProgressEvent) -> Unit,
): List<Path> {
    val settingsGradlePath = buildPath.createSettingsGradle(buildRequest, gradlePluginJars)
    buildPath.createBuildGradle()
    buildPath.createLocalProperties(buildRequest)

    require(gradleLogStdoutPath.notExists()) {
        "Log file for Gradle stdout already exists: ${gradleLogStdoutPath.pathString}"
    }
    require(gradleLogStdoutPath.notExists()) {
        "Log file for Gradle stderr already exists: ${gradleLogStderrPath.pathString}"
    }

    GradleConnector
        .newConnector()
        .forProjectDirectory(settingsGradlePath.parent.toFile())
        .apply {
            System.getenv(GRADLE_DISTRIBUTION_URL_ENV)
                ?.takeIf { it.isNotBlank() }
                ?.let { useDistribution(URI(it)) }
        }
        .connect()
        .use { connection ->
            val androidProjects = connection.extractAndroidProjectModelsFromBuild(buildRequest, jdkDir, debug)
            val lazyArtifacts = buildList {
                for (target in buildRequest.targets) {
                    val androidProject = androidProjects[target] ?: continue
                    addAll(androidProject.lazyArtifacts(connection, buildRequest, jdkDir, debug))
                    when(buildRequest.phase) {
                        AndroidBuildRequest.Phase.Test -> { /* nothing to do here, just return the artifact */ }
                        else -> {
                            val tasks = androidProject.taskList(connection, buildRequest, target, jdkDir)
                            try {
                                gradleLogStdoutPath.outputStream(WRITE, CREATE, APPEND).buffered().use { stdout ->
                                    gradleLogStderrPath.outputStream(WRITE, CREATE, APPEND).buffered().use { stderr ->
                                        connection.runBuild(
                                            tasks = tasks,
                                            eventHandler = eventHandler,
                                            stdoutStream = stdout,
                                            stderrStream = stderr,
                                            buildRequest = buildRequest,
                                            debug = debug,
                                            jdkDir = jdkDir,
                                        )
                                    }
                                }
                            } catch (t: RuntimeException) {
                                throw IllegalStateException("Error during Gradle build", t)
                            }
                        }
                    }
                }
            }

            return lazyArtifacts.map { it.value }
        }
}

private fun Path.createBuildGradle() {
    val buildGradlePath = this / "build.gradle.kts"
    val buildGradleFile = buildGradlePath.toFile()
    buildGradleFile.createNewFile()
}

private fun Path.createLocalProperties(buildRequest: AndroidBuildRequest): Path {
    val localPropertiesPath = this / "local.properties"
    val localPropertiesFile = localPropertiesPath.toFile()
    localPropertiesFile.createNewFile()
    val properties = Properties()
    properties.setProperty("sdk.dir", buildRequest.sdkDir?.pathString)
    localPropertiesFile.writer().use { properties.store(it, null) }
    return localPropertiesPath
}

private fun Path.createSettingsGradle(buildRequest: AndroidBuildRequest, gradlePluginJars: List<Path>): Path {
    val settingsGradlePath = this / "settings.gradle.kts"
    val settingsGradleFile = settingsGradlePath.toFile()
    settingsGradleFile.createNewFile()
    settingsGradleFile.writeText(
        """
buildscript {
    configurations {
        classpath {
            attributes {
                attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "jvm")
            }
        }
    }
    
    dependencies {
        classpath(files(${gradlePluginJars.joinToString(separator = ", ") { "\"${it.absolute().invariantSeparatorsPathString}\"" }}))
    }
}

plugins.apply(org.jetbrains.amper.android.gradle.AmperAndroidIntegrationSettingsPlugin::class.java)

configure<org.jetbrains.amper.android.gradle.AmperAndroidIntegrationExtension> {
    jsonData = ${"\"\"\""}${Json.encodeToString(buildRequest).replace("$", "\${'$'}")}${"\"\"\""}
}
""".trimIndent()
    )
    return settingsGradlePath
}

private fun AndroidProject.lazyArtifacts(
    connection: ProjectConnection,
    buildRequest: AndroidBuildRequest,
    jdkDir: Path,
    debug: Boolean = false,
): List<LazyArtifact> = buildList {
    for (buildType in buildRequest.buildTypes) {
        variants.filter { it.name == buildType.value }.forEach { variant ->
            when (buildRequest.phase) {
                AndroidBuildRequest.Phase.Prepare -> variant.mainArtifact.classesFolders.map { it.toPath() }
                    .forEach { add(DirectLazyArtifact(it)) }

                AndroidBuildRequest.Phase.Build -> add(
                    redirect(variant.mainArtifact.assembleTaskOutputListingFile ?: error("File must exist"))
                )

                AndroidBuildRequest.Phase.Bundle -> add(
                    redirect(variant.mainArtifact.bundleInfo?.bundleTaskOutputListingFile ?: error("File must exist"))
                )

                AndroidBuildRequest.Phase.Test -> {
                    val actionLauncher = connection.action { it.findModel(MockableJarModel::class.java).file }
                    actionLauncher.run(buildRequest, jdkDir, debug)?.toPath()?.let {
                        add(DirectLazyArtifact(it))
                    }
                }
            }
        }
    }
}

private fun AndroidProject.taskList(
    connection: ProjectConnection,
    buildRequest: AndroidBuildRequest,
    projectPath: String,
    jdkDir: Path,
): List<String> = buildList {
    for (buildType in buildRequest.buildTypes) {
        for (variant in variants.filter { it.name == buildType.value }) {
            val taskName = when (buildRequest.phase) {
                AndroidBuildRequest.Phase.Prepare -> {
                    val processResourcesProviderData = connection.model(ProcessResourcesProviderData::class.java)
                        .setJavaHome(jdkDir.toFile())
                        .get()
                    processResourcesProviderData.data[projectPath]?.get(variant.name)
                        ?: error("Incorrect ProcessResourcesProviderData for variant: ${variant.displayName}, data: $processResourcesProviderData")
                }
                AndroidBuildRequest.Phase.Build -> variant.mainArtifact.assembleTaskName
                AndroidBuildRequest.Phase.Bundle -> variant.mainArtifact.bundleInfo?.bundleTaskName
                    ?: error("Bundle info not found for variant: ${variant.displayName}")

                else -> error("Building task list for phase: ${buildRequest.phase} is not supported")
            }

            for (target in buildRequest.targets) {
                if (target == ":") {
                    add(":$taskName")
                } else {
                    add("$target:$taskName")
                }
            }
        }
    }
}

private fun ProjectConnection.runBuild(
    tasks: List<String>,
    eventHandler: (ProgressEvent) -> Unit,
    stdoutStream: BufferedOutputStream,
    stderrStream: BufferedOutputStream,
    buildRequest: AndroidBuildRequest,
    debug: Boolean,
    jdkDir: Path,
) {
    val buildLauncher = newBuild()
        .setJavaHome(jdkDir.toFile())
        .forTasks(*tasks.toTypedArray())
        .withArguments("--stacktrace")
        .addJvmArguments("-Xmx4G", "-XX:MaxMetaspaceSize=1G")
        .addDebugJvmArgumentsIf(debug)
        .forwardEnvironment(buildRequest)
        .addProgressListener(ProgressListener { eventHandler(it) })
        .setStandardOutput(stdoutStream)
        .setStandardError(stderrStream)

    buildLauncher.run()
}

private fun ProjectConnection.extractAndroidProjectModelsFromBuild(
    buildRequest: AndroidBuildRequest,
    jdkDir: Path,
    debug: Boolean,
): Map<String, AndroidProject> {
    val actionLauncher = action { controller ->
        val gradleProject = controller.findModel(GradleProject::class.java)
        val q = ArrayDeque<GradleProject>()
        q.add(gradleProject)
        buildMap {
            while (q.isNotEmpty()) {
                val project = q.removeFirst()
                project.children.forEach { q.add(it) }
                val androidProject = controller.findModel(project, AndroidProject::class.java)
                if (androidProject != null) {
                    put(project.path, androidProject)
                }
            }
        }
    }

    return actionLauncher.run(buildRequest, jdkDir, debug)
}

private fun <T> BuildActionExecuter<T>.run(buildRequest: AndroidBuildRequest, jdkDir: Path, debug: Boolean): T =
    setJavaHome(jdkDir.toFile())
        .forwardEnvironment(buildRequest)
        .addDebugJvmArgumentsIf(debug)
        .run()
