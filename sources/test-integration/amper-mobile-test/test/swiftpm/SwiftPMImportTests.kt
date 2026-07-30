/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package swiftpm

import io.opentelemetry.sdk.trace.data.SpanData
import iosUtils.IOSBaseTest
import iosUtils.SimulatorManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.simctl.SimCtl
import org.jetbrains.amper.simctl.TargetDevice
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.processes.TestReporterProcessOutputListener
import org.jetbrains.amper.test.spans.SpansTestCollector
import org.jetbrains.amper.test.spans.spansNamed
import kotlin.collections.mutableListOf
import kotlin.io.path.appendText
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.useLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@ProcessLeak
open class SwiftPMImportTests : IOSBaseTest() {

    @Test
    fun `smoke test Xcode project check, integration and package generation`() = runBlocking {
        val project = copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/direct-local-swiftpm-dependency"))
        val moduleFile = project.resolve("module.yaml")
        moduleFile.writeText(
            """
                product: ios/app
                
                settings:
                  kotlin:
                    version: "2.4.0"
            """.trimIndent()
        )

        // generate the project
        runAmper(
            workingDir = project,
            args = listOf("ide-integration", "manage-xcode"),
            environment = baseEnvironmentForWrapper(),
        )

        moduleFile.writeText(
            """
                product: ios/app

                dependencies:
                  - localSwiftPackage:
                      path: "packageDependency"
                      products: [ "packageProduct" ]
                
                settings:
                  kotlin:
                    version: "2.4.0"
            """.trimIndent()
        )

        val ddPath = project / "DerivedData"
        suspend fun runXcodebuild() = runProcess(
            workingDir = project,
            command = listOf(
                "xcodebuild", "build",
                "-project", "module.xcodeproj",
                "-scheme", "app",
                "-destination", "generic/platform=iOS Simulator",
                "-derivedDataPath", ddPath.pathString,
                "ARCHS=arm64",
            ),
            environment = baseEnvironmentForWrapper(),
            outputMode = ProcessOutputMode.listenAndCapture(TestReporterProcessOutputListener("xcodebuild", testReporter)),
        )

        val failingXcodebuildResult = runXcodebuild()
        assertContains(
            failingXcodebuildResult.stdout,
            """
                ERROR: Task ':direct-local-swiftpm-dependency:integrateLinkagePackage' failed: Your project uses SwiftPM dependencies
                Xcode project has been integrated with Kotlin-managed SwiftPM package
                Please rebuild the project
            """.trimIndent(),
        )
        assertEquals(65, failingXcodebuildResult.exitCode)

        val succeedingXcodebuildResult = runXcodebuild()
        assertEquals(0, succeedingXcodebuildResult.exitCode)

        val latestSimulator = SimulatorManager.launchLatestIPhoneSimulator()
        SimCtl.installApp(
            appFile = ddPath.resolve("Build/Products/Debug-iphonesimulator/direct-local-swiftpm-dependency.app"),
            device = TargetDevice.SpecificDevice(latestSimulator)
        )
        val appRunResult = SimCtl.launchAndWaitForAppToFinish(
            appBundleId = "direct-local-swiftpm-dependency",
            device = TargetDevice.SpecificDevice(latestSimulator)
        )

        assertEquals(
            """
                Hello from Swift
                Hello from ObjC
                Hello from Swift
                Hello from ObjC
            """.trimIndent(),
            appRunResult.stdout.trim().lines().dropLast(1).joinToString("\n")
        )
        assertEquals(
            "",
            appRunResult.stderr,
        )
        assertEquals(0, appRunResult.exitCode)
    }

    @Test
    fun `test package integration happens implicitly during build`() = runBlocking {
        val project = copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/direct-local-swiftpm-dependency"))
        project.resolve("module.yaml").writeText(
            """
                product: ios/app

                dependencies:
                  - localSwiftPackage:
                      path: "packageDependency"
                      products: [ "packageProduct" ]
                
                settings:
                  kotlin:
                    version: "2.4.0"
            """.trimIndent()
        )

        runAmper(
            workingDir = project,
            args = listOf("build"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        Unit
    }

    @Test
    fun `test package integration happens implicitly during build - with existing Xcode project`() = runBlocking {
        val project = copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/direct-local-swiftpm-dependency"))
        val moduleFile = project.resolve("module.yaml")
        moduleFile.writeText(
            """
                product: ios/app

                settings:
                  kotlin:
                    version: "2.4.0"
            """.trimIndent()
        )

        // generate the project
        runAmper(
            workingDir = project,
            args = listOf("ide-integration", "manage-xcode"),
            environment = baseEnvironmentForWrapper(),
        )

        moduleFile.writeText(
            """
                product: ios/app

                dependencies:
                  - localSwiftPackage:
                      path: "packageDependency"
                      products: [ "packageProduct" ]
                
                settings:
                  kotlin:
                    version: "2.4.0"
            """.trimIndent()
        )

        runAmper(
            workingDir = project,
            args = listOf("build"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        Unit
    }

    @Test
    fun `smoke test firebase integration`() = runBlocking {
        val project = copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/firebase"))
        runAmper(
            workingDir = project,
            args = listOf("ide-integration", "manage-xcode"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )

        val ddPath = project / "DerivedData"
        val xcodebuildResult = runProcess(
            workingDir = project,
            command = listOf(
                "xcodebuild", "build",
                "-project", "module.xcodeproj",
                "-scheme", "app",
                "-destination", "generic/platform=iOS Simulator",
                "-derivedDataPath", ddPath.pathString,
                "ARCHS=arm64",
            ),
            environment = baseEnvironmentForWrapper(),
            outputMode = ProcessOutputMode.listen(TestReporterProcessOutputListener("xcodebuild", testReporter)),
        )
        assertEquals(0, xcodebuildResult.exitCode)

        val inputKlib = project.resolve("build/generated/firebase/iosSimulatorArm64/cinterop/firebase-cinterop-firebase_swiftPMImport.klib")
        assert(inputKlib.exists())

        val outputDump = project.resolve("outputDump")
        runAmper(
            workingDir = project,
            args = listOf("task", ":firebase:dumpKlib"),
            environment = baseEnvironmentForWrapper() + mapOf(
                "INPUT_KLIB" to inputKlib.pathString,
                "OUTPUT_DUMP" to outputDump.pathString,
            ),
        )

        val firebaseApis = expectedFirebaseApis.mapValues { mutableListOf<String>() }.toMutableMap()

        outputDump.useLines { lines ->
            lines.forEach { line ->
                firebaseApis.keys.firstOrNull {
                    it in line
                }?.let {
                    firebaseApis[it]?.add(line)
                }
            }
        }

        assertEquals(
            expectedFirebaseApis,
            firebaseApis.mapValues { it.value.joinToString("\n") },
        )

        val latestSimulator = SimulatorManager.launchLatestIPhoneSimulator()
        SimCtl.installApp(
            appFile = ddPath.resolve("Build/Products/Debug-iphonesimulator/firebase.app"),
            device = TargetDevice.SpecificDevice(latestSimulator)
        )
        val appRunResult = SimCtl.launchAndWaitForAppToFinish(
            appBundleId = "firebase",
            device = TargetDevice.SpecificDevice(latestSimulator)
        )

        assertEquals(
            """
                Calling Firebase from Kotlin
                Returning from Swift
            """.trimIndent(),
            // Last line will be "firebase: ${pid}"
            appRunResult.stdout.trim().lines().dropLast(1).joinToString("\n")
        )
        assertEquals(
            "",
            appRunResult.stderr,
        )
        assertEquals(0, appRunResult.exitCode)
    }

    @Test
    fun `smoke test transitive SwiftPM dependencies`() = runBlocking {
        val project =
            copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/transitive-swiftpm-dependency"))
        runAmper(
            workingDir = project,
            args = listOf("ide-integration", "manage-xcode"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )

        val ddPath = project / "DerivedData"
        val xcodebuildResult = runProcess(
            workingDir = project.resolve("app"),
            command = listOf(
                "xcodebuild", "build",
                "-project", "module.xcodeproj",
                "-scheme", "app",
                "-destination", "generic/platform=iOS Simulator",
                "-derivedDataPath", ddPath.pathString,
                "ARCHS=arm64",
            ),
            environment = baseEnvironmentForWrapper(),
            outputMode = ProcessOutputMode.listen(TestReporterProcessOutputListener("xcodebuild", testReporter)),
        )
        assertEquals(0, xcodebuildResult.exitCode)

        val latestSimulator = SimulatorManager.launchLatestIPhoneSimulator()
        SimCtl.installApp(
            appFile = ddPath.resolve("Build/Products/Debug-iphonesimulator/app.app"),
            device = TargetDevice.SpecificDevice(latestSimulator)
        )
        val appRunResult = SimCtl.launchAndWaitForAppToFinish(
            appBundleId = "app",
            device = TargetDevice.SpecificDevice(latestSimulator)
        )

        assertEquals(
            """
                Hello from Swift
                Hello from Swift
                Hello from ObjC
            """.trimIndent(),
            // Last line will be "firebase: ${pid}"
            appRunResult.stdout.trim().lines().dropLast(1).joinToString("\n")
        )
        assertEquals(
            "",
            appRunResult.stderr,
        )
        assertEquals(0, appRunResult.exitCode)
    }

    internal fun AmperCliResult.readTelemetrySpans(): SpansTestCollector {
        return object : SpansTestCollector {
            override val spans: List<SpanData> = telemetrySpans
            override fun clearSpans() = throw UnsupportedOperationException("Cannot modify deserialized spans")
        }
    }

    internal fun AmperCliResult.withTelemetrySpans(block: SpansTestCollector.() -> Unit) {
        readTelemetrySpans().apply(block)
    }

    internal val SpansTestCollector.computeLocalPackageInputsSpans
        get() = spansNamed("compute local package inputs")

    internal val SpansTestCollector.internalPackageGenerationSpans
        get() = spansNamed("internal-package-generation")

    internal val SpansTestCollector.packageFetchSpans
        get() = spansNamed("fetch package")

    internal val SpansTestCollector.swiftPMImportSpans
        get() = spansNamed("swiftPMImport")

    @Test
    fun `test incremental local package task invalidation`() = runBlocking {
        val project =
            copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/transitive-swiftpm-dependency"))

        val initialRun = runAmper(
            workingDir = project,
            args = listOf("task", ":app:swiftPMImportIphoneos"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        initialRun.withTelemetrySpans {
            computeLocalPackageInputsSpans.assertSingle()
            internalPackageGenerationSpans.assertSingle()
            packageFetchSpans.assertSingle()
            swiftPMImportSpans.assertSingle()
        }

        val noChangesIncrementalRun = runAmper(
            workingDir = project,
            args = listOf("--log-level=debug", "task", ":app:swiftPMImportIphoneos"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        noChangesIncrementalRun.withTelemetrySpans {
            computeLocalPackageInputsSpans.assertNone()
            internalPackageGenerationSpans.assertNone()
            packageFetchSpans.assertNone()
            swiftPMImportSpans.assertNone()
        }

        project.resolve(
            "transitive/packageDependency/Sources/ObjCCompatibleSwiftTarget/ObjCCompatibleSwiftTarget.swift"
        ).appendText("\n")
        val sourceChangeIncrementalRun = runAmper(
            workingDir = project,
            args = listOf("--log-level=debug", "task", ":app:swiftPMImportIphoneos"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        sourceChangeIncrementalRun.withTelemetrySpans {
            computeLocalPackageInputsSpans.assertNone()
            internalPackageGenerationSpans.assertNone()
            packageFetchSpans.assertNone()
            swiftPMImportSpans.assertSingle()
        }

        project.resolve(
            "transitive/packageDependency/Package.swift"
        ).appendText("\n")
        val manifestChangeIncrementalRun = runAmper(
            workingDir = project,
            args = listOf("--log-level=debug", "task", ":app:swiftPMImportIphoneos"),
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        manifestChangeIncrementalRun.withTelemetrySpans {
            computeLocalPackageInputsSpans.assertSingle()
            internalPackageGenerationSpans.assertNone()
            packageFetchSpans.assertSingle()
            swiftPMImportSpans.assertSingle()
        }
    }

    @Test
    fun `test incremental static linkage and test runs on iOS Simulator and macOS`() = runBlocking {
        testIncrementalLinkageAndIncrementalTestRuns("static")
    }

    @Test
    fun `test incremental dynamic linkage and test runs on iOS Simulator and macOS`() = runBlocking {
        testIncrementalLinkageAndIncrementalTestRuns("dynamic")
    }

    private suspend fun testIncrementalLinkageAndIncrementalTestRuns(linkageType: String) {
        val project =
            copyProjectToTempDir(ProjectSource.Local(Dirs.amperTestProjectsRoot / "swiftpm-integration-tests/linkage-tests"))
        val initialExpectedOutput = "Hello from Swift"

        runAmper(
            workingDir = project,
            args = listOf("--log-level=debug", "test"),
            environment = baseEnvironmentForWrapper() + mapOf(
                "LINKAGE_TYPE" to linkageType,
                "EXPECTED_OUTPUT" to initialExpectedOutput,
                "SIMCTL_CHILD_EXPECTED_OUTPUT" to initialExpectedOutput,
            ),
            assertEmptyStdErr = false,
        )

        val finalExpectedOutput = "Hello from Swift2"
        project.resolve("packageDependency/Sources/ObjCCompatibleSwiftTarget/ObjCCompatibleSwiftTarget.swift").apply {
            writeText(readText().replace(initialExpectedOutput, finalExpectedOutput))
        }

        runAmper(
            workingDir = project,
            args = listOf("--log-level=debug", "test"),
            environment = baseEnvironmentForWrapper() + mapOf(
                "LINKAGE_TYPE" to linkageType,
                "EXPECTED_OUTPUT" to finalExpectedOutput,
                "SIMCTL_CHILD_EXPECTED_OUTPUT" to finalExpectedOutput,
            ),
            assertEmptyStdErr = false,
        )
    }

    private companion object {
        val expectedFirebaseApis = mapOf(
            "FIRApp." to """
                    swiftPMImport.firebase/FIRApp.<init>|objc:init#Constructor[100]
                    swiftPMImport.firebase/FIRApp.Companion|null[100]
                    swiftPMImport.firebase/FIRApp.dataCollectionDefaultEnabled.<get-dataCollectionDefaultEnabled>|objc:isDataCollectionDefaultEnabled#Accessor[100]
                    swiftPMImport.firebase/FIRApp.dataCollectionDefaultEnabled.<set-dataCollectionDefaultEnabled>|objc:setDataCollectionDefaultEnabled:#Accessor[100]
                    swiftPMImport.firebase/FIRApp.dataCollectionDefaultEnabled|{}dataCollectionDefaultEnabled[100]
                    swiftPMImport.firebase/FIRApp.deleteApp|objc:deleteApp:[100]
                    swiftPMImport.firebase/FIRApp.init|objc:init[100]
                    swiftPMImport.firebase/FIRApp.isDataCollectionDefaultEnabled|objc:isDataCollectionDefaultEnabled[100]
                    swiftPMImport.firebase/FIRApp.name.<get-name>|objc:name#Accessor[100]
                    swiftPMImport.firebase/FIRApp.name|objc:name[100]
                    swiftPMImport.firebase/FIRApp.name|{}name[100]
                    swiftPMImport.firebase/FIRApp.options.<get-options>|objc:options#Accessor[100]
                    swiftPMImport.firebase/FIRApp.options|objc:options[100]
                    swiftPMImport.firebase/FIRApp.options|{}options[100]
                    swiftPMImport.firebase/FIRApp.setDataCollectionDefaultEnabled|objc:setDataCollectionDefaultEnabled:[100]
                    swiftPMImport.firebase/container.<get-container>|FIRApp.objc:container#Accessor[100]
                    swiftPMImport.firebase/container.<set-container>|FIRApp.objc:setContainer:#Accessor[100]
                    swiftPMImport.firebase/container|FIRApp.objc:container[100]
                    swiftPMImport.firebase/heartbeatLogger.<get-heartbeatLogger>|FIRApp.objc:heartbeatLogger#Accessor[100]
                    swiftPMImport.firebase/heartbeatLogger|FIRApp.objc:heartbeatLogger[100]
                    swiftPMImport.firebase/initInstanceWithName|FIRApp.objc:initInstanceWithName:options:[100]
                    swiftPMImport.firebase/isDefaultApp.<get-isDefaultApp>|FIRApp.objc:isDefaultApp#Accessor[100]
                    swiftPMImport.firebase/isDefaultApp|FIRApp.objc:isDefaultApp[100]
                    swiftPMImport.firebase/setContainer|FIRApp.objc:setContainer:[100]
                """.trimIndent(),
            "FIRFirestore." to """
                    swiftPMImport.firebase/FIRFirestore.<init>|objc:init#Constructor[100]
                    swiftPMImport.firebase/FIRFirestore.Companion|null[100]
                    swiftPMImport.firebase/FIRFirestore.addSnapshotsInSyncListener|objc:addSnapshotsInSyncListener:[100]
                    swiftPMImport.firebase/FIRFirestore.app.<get-app>|objc:app#Accessor[100]
                    swiftPMImport.firebase/FIRFirestore.app|objc:app[100]
                    swiftPMImport.firebase/FIRFirestore.app|{}app[100]
                    swiftPMImport.firebase/FIRFirestore.batch|objc:batch[100]
                    swiftPMImport.firebase/FIRFirestore.clearPersistenceWithCompletion|objc:clearPersistenceWithCompletion:[100]
                    swiftPMImport.firebase/FIRFirestore.collectionGroupWithID|objc:collectionGroupWithID:[100]
                    swiftPMImport.firebase/FIRFirestore.collectionWithPath|objc:collectionWithPath:[100]
                    swiftPMImport.firebase/FIRFirestore.disableNetworkWithCompletion|objc:disableNetworkWithCompletion:[100]
                    swiftPMImport.firebase/FIRFirestore.documentWithPath|objc:documentWithPath:[100]
                    swiftPMImport.firebase/FIRFirestore.enableNetworkWithCompletion|objc:enableNetworkWithCompletion:[100]
                    swiftPMImport.firebase/FIRFirestore.getQueryNamed|objc:getQueryNamed:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.init|objc:init[100]
                    swiftPMImport.firebase/FIRFirestore.loadBundleStream|objc:loadBundleStream:[100]
                    swiftPMImport.firebase/FIRFirestore.loadBundleStream|objc:loadBundleStream:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.loadBundle|objc:loadBundle:[100]
                    swiftPMImport.firebase/FIRFirestore.loadBundle|objc:loadBundle:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.persistentCacheIndexManager.<get-persistentCacheIndexManager>|objc:persistentCacheIndexManager#Accessor[100]
                    swiftPMImport.firebase/FIRFirestore.persistentCacheIndexManager|objc:persistentCacheIndexManager[100]
                    swiftPMImport.firebase/FIRFirestore.persistentCacheIndexManager|{}persistentCacheIndexManager[100]
                    swiftPMImport.firebase/FIRFirestore.runTransactionWithBlock|objc:runTransactionWithBlock:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.runTransactionWithOptions|objc:runTransactionWithOptions:block:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.setIndexConfigurationFromJSON|objc:setIndexConfigurationFromJSON:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.setIndexConfigurationFromStream|objc:setIndexConfigurationFromStream:completion:[100]
                    swiftPMImport.firebase/FIRFirestore.setSettings|objc:setSettings:[100]
                    swiftPMImport.firebase/FIRFirestore.settings.<get-settings>|objc:settings#Accessor[100]
                    swiftPMImport.firebase/FIRFirestore.settings.<set-settings>|objc:setSettings:#Accessor[100]
                    swiftPMImport.firebase/FIRFirestore.settings|objc:settings[100]
                    swiftPMImport.firebase/FIRFirestore.settings|{}settings[100]
                    swiftPMImport.firebase/FIRFirestore.terminateWithCompletion|objc:terminateWithCompletion:[100]
                    swiftPMImport.firebase/FIRFirestore.useEmulatorWithHost|objc:useEmulatorWithHost:port:[100]
                    swiftPMImport.firebase/FIRFirestore.waitForPendingWritesWithCompletion|objc:waitForPendingWritesWithCompletion:[100]
                """.trimIndent()
        )
    }
}
