/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.lazyload.ExtraClasspath
import org.jetbrains.amper.frontend.plugins.PluginManifest
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.PluginDataResponse
import org.jetbrains.amper.plugins.schema.model.PluginDeclarationsRequest
import org.jetbrains.amper.plugins.schema.model.diagnostics.KotlinSchemaBuildProblem
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.relativeTo

@Serializable
data class PluginDataWithDiagnostics(
    val pluginData: PluginData,
    val diagnostics: List<KotlinSchemaBuildProblem>,
)

suspend fun runAmperSchemaProcessor(
    projectRoot: AmperProjectRoot,
    plugins: Map<Path, PluginManifest>,
    processRunner: ProcessRunner,
): List<PluginDataWithDiagnostics> {
    val toolClasspath = ExtraClasspath.PLUGINS_PROCESSOR.findJarsInDistribution()
    val apiClasspath = ExtraClasspath.EXTENSIBILITY_API.findJarsInDistribution()
    val request = PluginDeclarationsRequest(
        librariesPaths = apiClasspath,
        requests = plugins.map { [pluginRootPath, pluginInfo] ->
            PluginDeclarationsRequest.Request(
                moduleName = pluginRootPath.relativeTo(projectRoot.path).joinToString(":"),
                sourceDir = pluginRootPath / "src",
                pluginSettingsClassName = pluginInfo.settingsClass,
            )
        }
    )

    val result = processRunner.runJava(
        jdk = AmperJre,
        workingDir = Path("."),
        mainClass = "org.jetbrains.amper.schema.processing.MainKt",
        programArgs = emptyList(),
        argsMode = ArgsMode.CommandLine,
        classpath = toolClasspath,
        // Input request is passed via STDIN
        input = ProcessInput.text(Json.encodeToString(request)),
        outputMode = ProcessOutputMode.capture(),
    )

    if (result.exitCode != 0) {
        logger.error(result.stderr)
        error("Failed to process local plugin schema")
    }
    // Results are parsed from the process' STDOUT
    val response = try {
        Json.decodeFromString<PluginDataResponse>(result.stdout)
    } catch (e: SerializationException) {
        logger.error(result.stderr)
        throw e
    }

    val results = response.results
    val pluginDataWithDiagnostics = results.map { result ->
        val pluginRootDir = result.sourcePath.parent
        val plugin = checkNotNull(plugins[pluginRootDir]) {
            "Processing of ${result.sourcePath} requested, but no corresponding result is found"
        }
        PluginDataWithDiagnostics(
            pluginData = PluginData(
                id = plugin.id,
                pluginSettingsSearchResult = result.pluginSettingsSearchResult,
                description = plugin.description,
                source = PluginData.Source.Local(pluginRootDir),
                declarations = result.declarations,
            ),
            diagnostics = result.diagnostics,
        )
    }
    return pluginDataWithDiagnostics
}

private val logger = LoggerFactory.getLogger("runAmperSchemaProcessor")

/**
 * A current JRE that Amper process runs on.
 * Guaranteed only to have a `java` executable and to be able to run other Amper worker processes.
 */
private val AmperJre = Jdk(
    homeDir = Path(System.getProperty("java.home")!!),
    version = System.getProperty("java.version")!!,
    distribution = null,
    source = "The Kotlin Toolchain runtime",
)

