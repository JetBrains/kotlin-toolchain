/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.test.dr.toMavenCoordinates
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Resolved module dependencies graph for the test project 'compose-multiplatform' is almost identical to what Gradle resolves*.
 * Be careful: changing of the expected result might rather highlight
 * the error introduced to resolution logic than its improvement while DR evolving.
 *
 * Known sources of differences between Amper and Gradle resolution logic:
 *
 * 1. Gradle includes a dependency on 'org.jetbrains.compose.components:components-resources' unconditionally,
 *    while Amper adds this dependency in case the module does have 'compose' resources only.
 * 2. Amper resolves a runtime version of a library on IDE sync.
 *    This might cause a difference with the graph produced by Gradle.
 *    It will be fixed in the nearest future (as soon as Amper IDE plugin started calling
 *    CLI for running application instead of reusing module classpath from the Workspace model)
 */
class ModuleDependenciesGraphMultiplatformTest : BaseModuleDrTest() {
    override val testGoldenFilesRoot: Path = super.testGoldenFilesRoot / "moduleDependenciesGraphMultiplatform"

    @Test
    fun `test transitive swiftpm consumption`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("swiftpm-transitive", testDataRoot)
        val deps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput.copy(
                resolutionSettings = ideSyncTestResolutionInput.resolutionSettings.copy(
                    includeSwiftPMDependencies = true,
                    includeNonExportedNative = true,
                )
            ),
            module = "consumer",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        println(deps)
    }

    @Test
    fun `test sync empty jvm module`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("jvm-empty", testDataRoot)

        assertEquals(
            setOf("main", "test"),
            aom.modules[0].fragments.map { it.name }.toSet(),
            ""
        )

        val testFragmentDeps = doTestByFile(
            testInfo,
            aom,
            resolutionInput = ideSyncTestResolutionInput,
            module = "jvm-empty",
            filter = ideSyncModuleResolutionFilter
        )

        assertFiles(
            testInfo,
            testFragmentDeps
        )
    }

    @Test
    fun `test shared@ios dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val sharedIosFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "shared",
            fragment = "ios",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        assertFiles(testInfo, sharedIosFragmentDeps)
    }

    @Test
    fun `test shared@iosX64 dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)
        val iosAppIosX64FragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "shared",
            fragment = "iosX64",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )

        assertFiles(testInfo, iosAppIosX64FragmentDeps)
    }

    @Test
    fun `test shared@iosX64Test dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)
        val iosAppIosX64FragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "shared",
            fragment = "iosX64Test",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )

        assertFiles(testInfo, iosAppIosX64FragmentDeps)
    }

    /**
     * Since test fragment from one module can't reference the test fragment of another module,
     * exported test dependency 'tinylog-api-kotlin' of the shared module is not added to the fragment ios-app@iosX64Test.
     */
    @Test
    fun `test ios-app@iosX64Test dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)
        val iosAppIosX64FragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "ios-app",
            fragment = "iosX64Test",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )

        assertFiles(testInfo, iosAppIosX64FragmentDeps)
    }

    @Test
    fun `test ios-app@ios dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val iosAppIosFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "ios-app",
            fragment = "ios",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        assertFiles(testInfo, iosAppIosFragmentDeps)
    }

    @Test
    fun `test ios-app@iosX64 dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)
        val iosAppIosX64FragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "ios-app",
            fragment = "iosX64",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        assertFiles(testInfo, iosAppIosX64FragmentDeps)
    }

    // todo (AB) : 'android-app.android' differs from what Gradle produce (versions).
    // todo (AB) : It seems it is caused by resolving RUNTIME version of library instead of COMPILE one being resolved by IdeSync.
    //  Update: Versions in graph were slightly changed after ide sync started aligning versions across RUNTIME/COMPILE classpaths
    //          The issue might have been resolved.
    @Test
    fun `test android-app@android dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val androidAppAndroidFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "android-app",
            fragment = "main",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        // todo (AB) : Some versions are incorrect (?) - check difference with Gradle
        assertFiles(testInfo, androidAppAndroidFragmentDeps)
    }

    @Test
    fun `test shared@android dependencies graph`(testInfo: TestInfo) = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val sharedAndroidFragmentDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "shared",
            fragment = "android",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )
        // todo (AB) : Some versions are incorrect (?) - check difference with Gradle
        assertFiles(testInfo, sharedAndroidFragmentDeps)
    }

    @Test
    fun `test android transitive module dependencies fallback to jvm`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("android-transitive-jvm-fallback", testDataRoot)

        val androidAppDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            module = "android-app",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
        )
        assertFiles(testInfo, androidAppDeps)
    }

    /**
     * Publishing of a KMP library involves publishing various variants of this library for different platforms.
     * DR provides API [MavenDependencyNode.getMavenCoordinatesForPublishing]
     * for getting coordinates of the platform-specific variants of KMP libraries.
     * Those coordinates are used for the module publishing instead of references on KMP libraries.
     *
     * This test verifies that DR API provides correct coordinates of the platform-specific
     * variants for KMP libraries.
     */
    @Test
    fun `test publication of shared KMP module for single platform`() = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val sharedModuleDeps = doTest(
            aom,
            defaultTestResolutionInput.copy(
                resolutionSettings = defaultTestResolutionInput.resolutionSettings.copy(includeNonExportedNative = false)),
            module = "shared",
            filter = ModuleResolutionFilter(scope = ResolutionScope.COMPILE) // todo (AB) : ResolutionPlatform.JVM???
        )

        sharedModuleDeps.assertMapping(
            mapOf(
                "org.jetbrains.kotlin:kotlin-stdlib:${DefaultVersions.kotlin}" to "org.jetbrains.kotlin:kotlin-stdlib:${DefaultVersions.kotlin}",
                "org.jetbrains.compose.runtime:runtime:${DefaultVersions.compose}" to "org.jetbrains.compose.runtime:runtime-desktop:${DefaultVersions.compose}",
                "org.jetbrains.compose.foundation:foundation:${DefaultVersions.compose}" to "org.jetbrains.compose.foundation:foundation-desktop:${DefaultVersions.compose}",
                // Note: this has to be updated when changing the 'compose' version. See composeMaterial3VersionForCMPVersion() in catalog.kt
                "org.jetbrains.compose.material3:material3:1.10.0-alpha05" to "org.jetbrains.compose.material3:material3-desktop:1.10.0-alpha05",
            )
        )
    }

    /**
     * For platform-specific artifacts introduced into the resolution by KMP libraries
     * (as one of their `available-at`), DR provides API [MavenDependencyNode.getParentKmpLibraryCoordinates]
     * for getting coordinates of the original KMP libraries.
     * These coordinates are used in the IDE to deduplicate dependencies when searching for symbols that appear
     * both in the KMP library and in the platform-specific artifact.
     *
     * This test verifies that DR API provides correct coordinates of the KMP libraries
     * for platform-specific variants.
     */
    @Test
    fun `test finding KMP library for platform-specific variant`() = runSlowModuleDependenciesTest {
        val aom = getTestProjectModel("kmp-library", testDataRoot)

        val moduleDeps = doTest(
            aom,
            ideSyncTestResolutionInput,
            module = "kmp-library",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )

        moduleDeps.assertParentKmpLibraries(
            mapOf(
                "com.squareup.okio:okio-jvm:3.9.1" to "com.squareup.okio:okio:3.9.1",
                "com.squareup.okio:okio-iosarm64:3.9.1" to "com.squareup.okio:okio:3.9.1",
            ),
        )
    }

    private fun DependencyNode.assertMapping(
        expectedMapping: Map<String, String>
    ) {
        val expectedCoordinatesMapping =
            expectedMapping.map { it.key.toMavenCoordinates() to it.value.toMavenCoordinates() }.toMap()
        this
            .children
            .filterIsInstance<DirectFragmentDependencyNodeHolderWithContext>()
            .forEach { directMavenDependency ->
                val node = directMavenDependency.dependencyNode as MavenDependencyNode

                val originalCoordinates = node.getOriginalMavenCoordinates()
                val expectedCoordinatesForPublishing = expectedCoordinatesMapping[originalCoordinates]
                val actualCoordinatesForPublishing = node.getMavenCoordinatesForPublishing()

                assertNotNull(
                    expectedCoordinatesForPublishing,
                    "Library with coordinates [$originalCoordinates] is absent among direct module dependencies."
                ) {}
                assertEquals(
                    expectedCoordinatesForPublishing, actualCoordinatesForPublishing,
                    "Unexpected coordinates for publishing were resolved for the library [$originalCoordinates]"
                )
            }
    }

    private fun DependencyNode.assertParentKmpLibraries(
        expectedMapping: Map<String, String>
    ) {
        val expectedCoordinatesMapping =
            expectedMapping.map { it.key.toMavenCoordinates() to it.value.toMavenCoordinates() }.toMap()
        // Find all MavenDependencyNode instances in the dependency graph
        val allMavenNodes = mutableListOf<MavenDependencyNode>()

        fun collectMavenNodes(node: DependencyNode) {
            if (node is MavenDependencyNode) {
                allMavenNodes.add(node)
            }
            node.children.forEach { collectMavenNodes(it) }
        }

        collectMavenNodes(this)

        val nodeParents = allMavenNodes.mapNotNull { dep ->
            val parentCoordinates = dep.getParentKmpLibraryCoordinates() ?: return@mapNotNull null
            dep.getOriginalMavenCoordinates() to parentCoordinates
        }.toMap()
        assertEquals(expectedCoordinatesMapping, nodeParents, "Incorrect parent KMP libraries were resolved")
    }
}
