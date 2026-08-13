/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div

class DependencyCoordinatesTest: BaseModuleDrTest() {

    override val testGoldenFilesRoot: Path = super.testGoldenFilesRoot / "mavenCoordinates"

    /**
     * Checks that direct dependency with classifier is correctly resolved.
     * In particular, the module 'shared' of the test project 'full-maven-coordinates-support'
     * declares a direct exported dependency on the 'org.bytedeco:opencv:4.5.5-1.5.7:windows-x86_64'.
     * The classifier 'windows-x86_64' is a part of coordinates and should be taken into account.
     */
    @Test
    fun `direct dependency with classifier`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("full-maven-coordinates-support", testDataRoot)

        val jvmAppDeps = doTestByFile(
            testInfo,
            aom,
            filter = ModuleResolutionFilter(scope = ResolutionScope.COMPILE),
            module = "jvm-app",
        )

        assertFiles(
            testInfo,
            root = jvmAppDeps,
        )
    }

    /**
     * Checks that direct dependency with packaging type is correctly resolved.
     * In particular, the module 'jvm-app' of the test project 'full-maven-coordinates-support'
     * declares direct dependency
     *
     * - 'io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8'.
     *     It contains classifier 'jdk8' and specified no packaging type.
     *     Library 'io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8' is published without Gradle metadata,
     *     that means that packaging type from pom.xml will be used for resolution.
     *     It is equal to 'pom'. And in this case resolution will try to fallback on 'jar' artifact.
     */
    @Test
    fun `direct dependency with packaging type pom fallback to jar`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("full-maven-coordinates-support", testDataRoot)

        val jvmAppDeps = doTestByFile(
            testInfo,
            aom,
            filter = ModuleResolutionFilter(scope = ResolutionScope.COMPILE),
            module = "jvm-lib-1",
        )

        assertFiles(
            testInfo,
            root = jvmAppDeps,
        )
    }

    /**
     * Checks that direct dependency with packaging type is correctly resolved.
     * In particular, the module 'jvm-app' of the test project 'full-maven-coordinates-support'
     * declares direct dependency
     *
     * - 'io.grpc:protoc-gen-grpc-java:1.80.0:linux-x86_32@exe'
     *     It contains classifier 'linux-x86_32' and explicitly specifies packaging type 'exe'.
     */
    @Test
    fun `direct dependency with explicitly specified packagingType`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("full-maven-coordinates-support", testDataRoot)

        val jvmLib2Deps = doTestByFile(
            testInfo,
            aom,
            filter = ModuleResolutionFilter(scope = ResolutionScope.COMPILE),
            module = "jvm-lib-2",
        )

        assertFiles(
            testInfo,
            root = jvmLib2Deps,
        )
    }
}