/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.amper.test.Dirs
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions

/**
 * Failure modes of the fake `xcodebuild`, see `testResources/ios-publish/fake-xcodebuild.sh`.
 */
internal enum class FakeXcodebuildMode(val value: String) {
    Ok("ok"),
    ArchiveFail("archive-fail"),
    UploadFail("upload-fail"),
    UploadNoDistribution("upload-no-distribution"),
    UploadStateFailed("upload-state-failed"),
}

/**
 * A shadow Xcode developer directory that mirrors the real Xcode through symlinks, except for `usr/bin/xcodebuild`,
 * which is replaced by a fake that fakes `archive` and App Store Connect uploads and passes everything else through.
 *
 * It's selected by the CLI under test through the standard `DEVELOPER_DIR` environment variable, which both
 * `xcode-select -p` and `xcrun` respect, so no product code hook is needed.
 */
internal object FakeXcode {

    private val fakeXcodebuildScript: Path =
        Dirs.amperSourcesRoot / "test-integration/amper-cli-test/testResources/ios-publish/fake-xcodebuild.sh"

    /**
     * The developer directory of the real, selected Xcode, e.g. `/Applications/Xcode.app/Contents/Developer`.
     */
    val realDeveloperDir: Path by lazy { selectedRealDeveloperDir() }

    /**
     * The shadow developer directory, created once per test JVM.
     */
    val developerDir: Path by lazy { createShadowDeveloperDir() }

    fun configureEnvironment(
        env: MutableMap<String, String>,
        ascUrl: String,
        log: Path,
        mode: FakeXcodebuildMode = FakeXcodebuildMode.Ok,
        bundleId: String? = null,
    ) {
        env["DEVELOPER_DIR"] = developerDir.pathString
        env["FAKE_XCODEBUILD_REAL_DEVELOPER_DIR"] = realDeveloperDir.pathString
        env["FAKE_XCODEBUILD_LOG"] = log.pathString
        env["FAKE_XCODEBUILD_MODE"] = mode.value
        env["FAKE_ASC_URL"] = ascUrl
        if (bundleId != null) env["FAKE_XCODEBUILD_BUNDLE_ID"] = bundleId
    }

    /**
     * Reads the argv of every recorded `xcodebuild` invocation from the [log] file.
     */
    fun readInvocations(log: Path): List<List<String>> {
        if (!log.exists()) return []
        return log.readLines().filter { it.isNotBlank() }.map { line ->
            Json.parseToJsonElement(line).jsonArray.map { it.jsonPrimitive.content }
        }
    }

    private fun selectedRealDeveloperDir(): Path {
        val process = ProcessBuilder("/usr/bin/xcode-select", "-p")
            .apply { environment().remove("DEVELOPER_DIR") }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "xcode-select -p failed: $output" }
        check(output.endsWith("/Contents/Developer")) { "Unexpected Xcode developer directory: $output" }
        return Path(output)
    }

    private fun createShadowDeveloperDir(): Path {
        val realContents = realDeveloperDir.parent
        val fakeContents = Dirs.tempDir / "fake-xcode" / UUID.randomUUID().toString() / "Fake.app" / "Contents"
        val fakeDeveloperDir = fakeContents / "Developer"
        val fakeBin = fakeDeveloperDir / "usr" / "bin"
        mirror(realContents, fakeContents, except = "Developer")
        mirror(realDeveloperDir, fakeDeveloperDir, except = "usr")
        mirror(realDeveloperDir / "usr", fakeDeveloperDir / "usr", except = "bin")
        mirror(realDeveloperDir / "usr" / "bin", fakeBin, except = "xcodebuild")
        val fakeXcodebuild = fakeXcodebuildScript.copyTo(fakeBin / "xcodebuild")
        fakeXcodebuild.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return fakeDeveloperDir
    }

    /**
     * Creates [target] with a symlink to each entry of [source], except the one named [except].
     */
    private fun mirror(source: Path, target: Path, except: String) {
        target.createDirectories()
        source.listDirectoryEntries()
            .filter { it.name != except }
            .forEach { (target / it.name).createSymbolicLinkPointingTo(it) }
    }
}
