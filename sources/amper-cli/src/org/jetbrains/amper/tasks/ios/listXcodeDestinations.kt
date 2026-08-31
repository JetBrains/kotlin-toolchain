/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildPlatform
import java.nio.file.Path
import kotlin.io.path.absolutePathString

@JvmInline
value class XcodeDeviceId(val value: String) {
    override fun toString() = value
}

data class XcodeDestination(
    val id: XcodeDeviceId,
    val name: String,
    val platform: XcodebuildPlatform?,
    val arch: String?,
    val error: String?,
    val os: ComparableVersion?,
)

val XcodeDestination.isBuildOnly
    get() = id.let { ":placeholder" in it.value } && arch == null

suspend fun listXcodeDestinations(
    projectDir: Path,
    schemeName: String,
): List<XcodeDestination> {
    val result = runProcess(
        workingDir = projectDir,
        command = [
            "xcrun", "xcodebuild",
            "-project", projectDir.absolutePathString(),
            "-scheme", schemeName,
            "-showdestinations",
            "-quiet",
        ],
        outputMode = ProcessOutputMode.capture(),
    )
    /*
     No `--json` option available, have to parse semi-human readable output
     Example output:

     ```
     |
     |
     |    Available destinations for the "app" scheme:
     |        { platform:iOS, arch:arm64, id:00008253-000E67211A78001E, name:Some Device Name }
     |        { platform:iOS, id:dvtdevice-DVTiPhonePlaceholder-iphoneos:placeholder, name:Any iOS Device }
     |        { platform:iOS Simulator, id:dvtdevice-DVTiOSDeviceSimulatorPlaceholder-iphonesimulator:placeholder, name:Any iOS Simulator Device }
     |        { platform:iOS Simulator, arch:arm64, id:E518559A-3084-4260-A223-E8FFB923D11A, OS:26.5, name:iPhone 17 Pro }
     ```
     */
    return DestinationLineRegex.findAll(result.stdout)
        .map { it.groupValues[1] }
        .mapNotNull { value ->
            val data = value.split(",")
                .map { it.trim() }
                .associate { it.substringBefore(':') to it.substringAfter(':') }
            XcodeDestination(
                id = XcodeDeviceId(data["id"] ?: return@mapNotNull null),
                name = data["name"] ?: return@mapNotNull null,
                platform = data["platform"]?.let { platform ->
                    XcodebuildPlatform.entries.find { it.destination == platform }
                },
                arch = data["arch"],
                error = data["error"],
                os = data["OS"]?.let(::ComparableVersion),
            )
        }.toList()
}

private val DestinationLineRegex = """^\s*\{\s*(.*?)\s*\}\s*$""".toRegex(RegexOption.MULTILINE)
