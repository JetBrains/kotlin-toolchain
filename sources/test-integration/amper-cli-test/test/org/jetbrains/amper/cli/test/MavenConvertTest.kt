/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.test.assertEqualsIgnoreLineSeparator
import org.jetbrains.amper.test.processes.checkExitCodeIsZero
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MavenConvertTest : AmperCliTestBase() {
    @Test
    fun `spring-boot`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-boot")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "project.yaml").readText().isBlank())
        assertTrue((buildResult.projectDir / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/app

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: demo
                group: com.example
                version: 0.0.1-SNAPSHOT
              jvm:
                storeParameterNames: true
              springBoot:
                enabled: true
                version: 4.0.0

            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:4.0.0
              - bom: org.springframework.boot:spring-boot-starter-parent:4.0.0
              - org.springframework.boot:spring-boot-starter:4.0.0: exported

            test-dependencies:
              - org.springframework.boot:spring-boot-starter-test:4.0.0

        """.trimIndent(), (buildResult.projectDir / "module.yaml").readText()
        )

        val converted = testProject(buildResult.projectDir.pathString)

        runCli(
            converted,
            "test",
            // warning about mockito loaded dynamically
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `convert overwrites existing files when overwrite is enabled`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-boot")

        val firstRun = runCli(projectRoot, "tool", "convert-project")

        val moduleYaml = firstRun.projectDir / "module.yaml"
        assertTrue(moduleYaml.exists())

        moduleYaml.writeText("CHANGED")

        runCli(firstRun.projectDir, "tool", "convert-project", "--overwrite-existing")

        assertTrue(moduleYaml.exists())
        assertNotEquals("CHANGED", moduleYaml.readText())
    }

    @Test
    fun `convert fails when files already exist and overwrite is not enabled`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-boot")

        val firstRun = runCli(projectRoot, "tool", "convert-project")

        val secondRun = runCli(
            firstRun.projectDir,
            "tool", "convert-project",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        secondRun.assertStderrContains("File already exists")
    }

    @Test
    fun `spring-boot-kotlin`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-boot-kotlin")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/app

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: demo
                group: com.example
                version: 0.0.1-SNAPSHOT
              jvm:
                storeParameterNames: true
                release: 17
              kotlin:
                version: 2.3.21
                freeCompilerArgs:
                  - -Xjsr305=strict
                  - -Xannotation-default-target=param-property
              springBoot:
                enabled: true
                version: 4.0.0

            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:4.0.0
              - bom: org.springframework.boot:spring-boot-starter-parent:4.0.0
              - org.springframework.boot:spring-boot-starter:4.0.0: exported
              - org.jetbrains.kotlin:kotlin-reflect:2.3.21: exported
              - org.jetbrains.kotlin:kotlin-stdlib:2.3.21: exported

            test-dependencies:
              - org.springframework.boot:spring-boot-starter-test:4.0.0
              - org.jetbrains.kotlin:kotlin-test-junit5:2.3.21

        """.trimIndent(), (buildResult.projectDir / "module.yaml").readText()
        )

        val converted = testProject(buildResult.projectDir.pathString)

        runCli(
            converted,
            "test",
            // warning about mockito loaded dynamically
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `spring-petclinic`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-petclinic")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        val expectedProjectFile = projectRoot / "expected-project.yaml"
        val actualProjectFile = buildResult.projectDir.resolve("project.yaml")
        assertTrue(actualProjectFile.exists())
        assertEqualsIgnoreLineSeparator(
            expectedContent = expectedProjectFile.readText(),
            actualContent = actualProjectFile.readText(),
            originalFile = expectedProjectFile,
        )

        val expectedModuleFile = projectRoot.resolve("expected-module.yaml")
        val actualModuleFile = buildResult.projectDir / "module.yaml"
        assertTrue(actualModuleFile.exists())
        assertEqualsIgnoreLineSeparator(
            expectedContent = expectedModuleFile.readText(),
            actualContent = actualModuleFile.readText(),
            originalFile = expectedModuleFile,
        )
        val converted = testProject(buildResult.projectDir.pathString)

        runCli(
            converted,
            "test",
            // warning about mockito loaded dynamically
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `annotation-processing`() = runSlowTest {
        val projectRoot = testProject("maven-convert/annotation-processing")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/app
            
            layout: maven-like
            
            settings:
              publishing:
                enabled: true
                artifactId: demo18
                group: org.example
                version: 0.0.1-SNAPSHOT
              java:
                annotationProcessing:
                  processors:
                    - org.springframework.boot:spring-boot-configuration-processor
                    - org.projectlombok:lombok
              jvm:
                storeParameterNames: true
              springBoot:
                enabled: true
                version: 4.0.0
            
            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:4.0.0
              - bom: org.springframework.boot:spring-boot-starter-parent:4.0.0
              - org.springframework.boot:spring-boot-starter:4.0.0: exported
              - org.projectlombok:lombok:1.18.42: exported
            
            test-dependencies:
              - org.springframework.boot:spring-boot-starter-test:4.0.0

        """.trimIndent(), (buildResult.projectDir / "module.yaml").readText()
        )

        val converted = testProject(buildResult.projectDir.pathString)

        val testsResult = runCli(
            converted,
            "test",
            // warning about mockito loaded dynamically
            assertEmptyStdErr = false,
        )

        testsResult.assertStdoutContains("post construct")
    }

    @Test
    fun `multi-module`() = runSlowTest {
        val projectRoot = testProject("maven-convert/multi-module")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertEquals(
            """
            modules:
              - lib
              - app

        """.trimIndent(), (buildResult.projectDir / "project.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "app" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib
            
            layout: maven-like
            
            settings:
              publishing:
                enabled: true
                artifactId: app
                group: com.example
                version: 1.0.0
              jvm:
                release: 17
                storeParameterNames: true
            
            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:3.5.6
              - bom: org.springframework.boot:spring-boot-starter-parent:3.5.6
              - ../lib: exported

            test-dependencies:
              - org.junit.jupiter:junit-jupiter:5.12.2

        """.trimIndent(), (buildResult.projectDir / "app" / "module.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "lib" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: lib
                group: com.example
                version: 1.0.0
              jvm:
                release: 17
                storeParameterNames: true

            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:3.5.6
              - bom: org.springframework.boot:spring-boot-starter-parent:3.5.6

        """.trimIndent(), (buildResult.projectDir / "lib" / "module.yaml").readText()
        )

        assertTrue((buildResult.projectDir / "module.yaml").notExists())

        val converted = testProject(buildResult.projectDir.pathString)

        runCli(converted, "test")
    }

    @Test
    fun `multi-module-nested`() = runSlowTest {
        val projectRoot = testProject("maven-convert/multi-module-nested")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertEquals(
            """
            modules:
              - parent-only/nested-module

            """.trimIndent(), (buildResult.projectDir / "project.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "parent-only" / "nested-module" / "module.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").notExists())
        assertTrue((buildResult.projectDir / "parent-only" / "module.yaml").notExists())
    }

    @Test
    fun `not-a-maven-project`() = runSlowTest {
        val projectRoot = testProject("maven-convert/not-a-maven-project")

        val buildResult = runCli(
            projectRoot,
            "tool",
            "convert-project",
            expectedExitCode = 1,
            assertEmptyStdErr = false
        )

        buildResult.assertStderrContains("ERROR: pom.xml file not found")
    }

    @Test
    fun `pom-dependency-type-local`() = runSlowTest {
        val projectRoot = testProject("maven-convert/pom-dependency-type")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertEquals(
            """
            modules:
              - app
              - deps-pom

            """.trimIndent(), (buildResult.projectDir / "project.yaml").readText()
        )

        assertTrue((buildResult.projectDir / "app" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: app
                group: com.example
                version: 1.0.0

            dependencies:
              - ../deps-pom: exported

            """.trimIndent(), (buildResult.projectDir / "app" / "module.yaml").readText()
        )

        assertTrue((buildResult.projectDir / "deps-pom" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib
            
            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: deps-pom
                group: com.example
                version: 1.0.0

            dependencies:
              - org.slf4j:slf4j-api:2.0.9: exported
              - com.google.guava:guava:32.1.3-jre: runtime-only

            """.trimIndent(), (buildResult.projectDir / "deps-pom" / "module.yaml").readText()
        )
    }

    @Test
    fun `pom-dependency-type-external`() = runSlowTest {
        val projectRoot = testProject("maven-convert/pom-dependency-type-external")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())

        val pomPath = buildResult.projectDir / "pom.xml"

        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: pom-dependency-type-external-test
                group: com.example
                version: 1.0.0

            dependencies:
              - org.apache.commons:commons-lang3:3.20.0: exported
              # WARNING: The Kotlin Toolchain does not support external POM dependencies with scopes different than import, manual configuration may be required.
              # Reference: $pomPath:18:21
              # <dependency>
              #   <groupId>org.apache.hadoop</groupId>
              #   <artifactId>hadoop-client-check-invariants</artifactId>
              #   <version>3.3.6</version>
              #   <type>pom</type>
              #   <scope>compile</scope>
              # </dependency>

            """.trimIndent(), (buildResult.projectDir / "module.yaml").readText()
        )
    }


    @Test
    fun `surefire-plugin`() = runSlowTest {
        val projectDir = testProject("maven-convert/surefire-plugin")

        val buildResult = runCli(projectDir, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())

        assertEquals(
            """
            product: jvm/app

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: surefire-demo
                group: com.example
                version: 0.0.1-SNAPSHOT
              jvm:
                storeParameterNames: true
              springBoot:
                enabled: true
                version: 4.0.0

            dependencies:
              - bom: org.springframework.boot:spring-boot-dependencies:4.0.0
              - bom: org.springframework.boot:spring-boot-starter-parent:4.0.0
              - org.springframework.boot:spring-boot-starter:4.0.0: exported

            test-settings:
              jvm:
                test:
                  freeJvmArgs:
                    - -Xmx512m
                    - -ea
                  extraEnvironment:
                    MY_TEST_ENV: test-value
                    ANOTHER_ENV: another-value
                  systemProperties:
                    my.test.prop: prop-value
                    another.prop: another-prop-value
                  # WARNING: The following configuration is not supported in the Kotlin Toolchain:
                  # <includes>
                  #   <include>**/*Test.java</include>
                  # </includes>

            test-dependencies:
              - org.springframework.boot:spring-boot-starter-test:4.0.0

        """.trimIndent(), (buildResult.projectDir / "module.yaml").readText()
        )

        val converted = testProject(buildResult.projectDir.pathString)

        runCli(converted, "test")
    }


    @Test
    fun `parent with repository`() = runSlowTest {
        val projectRoot = testProject("maven-convert/parent-with-repository")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: app
                group: com.example
                version: 1.0.0

            repositories:
              - id: test-custom-repo
                url: https://custom.example.com/maven

        """.trimIndent(), (buildResult.projectDir / "app" / "module.yaml").readText()
        )
    }

    @Test
    fun `duplicate-executions`() = runSlowTest {
        val projectRoot = testProject("maven-convert/duplicate-executions")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        val pomPath = buildResult.projectDir / "pom.xml"
        val actualModuleFile = buildResult.projectDir / "module.yaml"
        val actualModuleSubstitutedPom = actualModuleFile
            .readText()
            .replace(pomPath.absolutePathString(), $$"$pom")
        val expectedModuleFile = projectRoot / "expected-module.yaml"
        assertEqualsIgnoreLineSeparator(
            expectedContent = expectedModuleFile.readText(),
            actualContent = actualModuleSubstitutedPom,
            originalFile = expectedModuleFile,
        )
    }

    @Test
    fun `transitive compile classpath visibility`() = runSlowTest {
        val projectRoot = testProject("maven-convert/transitive-compile-classpath-visibility")

        val buildResult = runCli(projectRoot, "tool", "convert-project")

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertEquals(
            """
            modules:
              - core
              - app

            """.trimIndent(), (buildResult.projectDir / "project.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "core" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: core
                group: com.example
                version: 1.0-SNAPSHOT

            dependencies:
              - com.fasterxml.jackson.core:jackson-databind:2.17.2: exported

            """.trimIndent(), (buildResult.projectDir / "core" / "module.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "app" / "module.yaml").exists())
        assertEquals(
            """
            product: jvm/lib

            layout: maven-like

            settings:
              publishing:
                enabled: true
                artifactId: app
                group: com.example
                version: 1.0-SNAPSHOT

            dependencies:
              - ../core: exported

            """.trimIndent(), (buildResult.projectDir / "app" / "module.yaml").readText()
        )
        assertTrue((buildResult.projectDir / "module.yaml").notExists())

        val converted = testProject(buildResult.projectDir.pathString)

        runCli(converted, "build")
    }

    @Test
    fun `blank plugin config value`() = runSlowTest {
        val projectRoot = testProject("maven-convert/blank-plugin-config-value")

        val buildResult = runCli(
            projectRoot,
            "tool",
            "convert-project",
        )

        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())

        val moduleYaml = (buildResult.projectDir / "module.yaml").readText()
        assertTrue(moduleYaml.contains("product: jvm/lib"))
    }

    @Test
    fun `commons-lang`() = runSlowTest {
        val projectRoot = testProject("maven-convert/commons-lang")
        val buildResult = runCli(
            projectRoot, "tool", "convert-project",
        )
        assertTrue((buildResult.projectDir / "project.yaml").exists())
        assertTrue((buildResult.projectDir / "module.yaml").exists())
        val moduleYaml = (buildResult.projectDir / "module.yaml").readText()
        assertTrue(moduleYaml.contains("layout: maven-like"))
        assertTrue(moduleYaml.contains("artifactId: commons-lang3"))
        assertTrue(moduleYaml.contains("group: org.apache.commons"))
    }

    @Test
    fun `spring-petclinic with enable-compatibility-plugins`() = runSlowTest {
        val projectRoot = testProject("maven-convert/spring-petclinic")

        val buildResult = runCli(
            projectRoot,
            "tool", "convert-project", "--enable-compatibility-plugins",
        )

        val moduleContent = (buildResult.projectDir / "module.yaml").readText()
        assertFalse(moduleContent.contains("enabled: false"), "All compatibility plugins should be enabled")
        // Plugins with configuration keep full form
        assertTrue(moduleContent.contains("enabled: true"), "Plugins with configuration should have enabled: true")
        // Plugins without configuration use shorthand form
        assertTrue(
            moduleContent.contains("maven-enforcer-plugin.display-info: enabled"),
            "Plugins without configuration should use shorthand 'enabled' form"
        )
        assertFalse(
            moduleContent.contains("Maven compatibility plugins are disabled by default"),
            "Disabled plugins hint comment should not be present"
        )
    }

    @Test
    fun `duplicate-executions with enable-compatibility-plugins`() = runSlowTest {
        val projectRoot = testProject("maven-convert/duplicate-executions")

        val buildResult = runCli(
            projectRoot,
            "tool", "convert-project", "--enable-compatibility-plugins",
        )

        val moduleContent = (buildResult.projectDir / "module.yaml").readText()
        assertFalse(moduleContent.contains("enabled: false"), "All compatibility plugins should be enabled")
        // Plugins without configuration use shorthand form
        assertTrue(
            moduleContent.contains(": enabled"),
            "Plugins without configuration should use shorthand 'enabled' form"
        )
        assertFalse(
            moduleContent.contains("Maven compatibility plugins are disabled by default"),
            "Disabled plugins hint comment should not be present"
        )
        // Duplicate execution warning comments should still be present
        assertTrue(
            moduleContent.contains("configured in multiple executions"),
            "Duplicate execution warnings should still be present"
        )
    }

    @Test
    fun `git-commit-id with enable-compatibility-plugins`() = runSlowTest {
        val projectRoot = testProject("maven-convert/git-commit-id")

        val buildResult = runCli(
            projectRoot,
            "tool", "convert-project", "--enable-compatibility-plugins",
        )

        val moduleContent = (buildResult.projectDir / "module.yaml").readText()
        // Plugin without configuration uses shorthand form
        assertTrue(
            moduleContent.contains("git-commit-id-maven-plugin.revision: enabled"),
            "git-commit-id-maven-plugin.revision should use shorthand 'enabled' form"
        )

        // Initialize a git repo so the git-commit-id plugin can run
        val dir = buildResult.projectDir
        runProcess(
            workingDir = dir,
            command = ["git", "init"],
            outputMode = ProcessOutputMode.Discard,
        ).checkExitCodeIsZero()
        runProcess(
            workingDir = dir,
            command = ["git", "add", "."],
            outputMode = ProcessOutputMode.Discard,
        ).checkExitCodeIsZero()
        runProcess(
            workingDir = dir,
            command = ["git", "-c", "user.name=test", "-c", "user.email=test@test.com", "commit", "-m", "initial"],
            outputMode = ProcessOutputMode.Discard,
        ).checkExitCodeIsZero()

        val converted = testProject(buildResult.projectDir.pathString)
        runCli(
            projectDir = converted,
            "test",
            // jgit cleanup message on JVM shutdown
            assertEmptyStdErr = false,
        )
    }

}
