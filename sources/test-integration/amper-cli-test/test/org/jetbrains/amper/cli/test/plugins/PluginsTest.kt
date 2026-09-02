/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.plugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertErrors
import org.jetbrains.amper.cli.test.utils.assertLogContains
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertWarnings
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.assertEqualsIgnoreLineSeparator
import org.jetbrains.amper.test.normalizeLineSeparators
import org.junit.jupiter.api.Tag
import org.slf4j.event.Level
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals

@Tag("cli-test-group-plugins")
class PluginsTest : CliTestBase() {
    @Test
    fun `single plugin - contributes source file when enabled`() = runSlowTest {
        val r1 = runCli(
            projectDir = testProject("extensibility/single-local-plugin"),
            "show", "tasks",
        )

        with(r1) {
            assertStdoutContains("task :app1:generate-konfig@build-konfig -> :build-konfig-plugin:runtimeClasspathJvm")
            assertStdoutContains(
                "task :app1:print-generated-sources@build-konfig -> " +
                        ":build-konfig-plugin:runtimeClasspathJvm, :app1:generate-konfig@build-konfig"
            )
        }

        val r2 = runCli(
            projectDir = r1.projectDir,
            "run", "-m", "app1",
        )

        r2.assertStdoutContains("version: 1.0+hello; id: table-green-geese")
    }

    @Test
    fun `distribution plugin`() = runSlowTest {
        val testProjectSourcesDir = testProject("extensibility/distribution")
        val r1 = runCli(
            projectDir = testProjectSourcesDir,
            "task", ":app:build@distribution-plugin",
        )

        val buildDir = tempRoot / "build"
        val projectDir = r1.projectDir
        val expectedOutputPath = testProjectSourcesDir / "expected-plugin-output.txt"

        // In the 'core' and 'lib' modules, the Kotlin version is not overridden, so we expect the default in the
        //   corresponding classpaths.
        // We expect Kotlin 2.3.0 specifically in the 'from-catalog' because the 'app' module
        //   overrides settings.kotlin.version.
        // We expect Kotlin 2.3.10 specifically in the 'compile' classpaths
        //   despite the fact that the 'app' module overrides settings.kotlin.version.
        //   In 'compile', the version is aligned with the runtime classpath of the module.
        //   The runtime classpath of the module gets the default Kotlin version transitively from core/lib because it's
        //   higher, so we expect the default Kotlin version even though it sets Kotlin to 2.2.10 explicitly.
        val actualOutputSubstituted = r1.extractCustomTaskStdout(
            moduleName = "app",
            taskName = "build",
            pluginName = "distribution-plugin",
        )
            .replace(projectDir.toString(), $$"$projectDir")
            .replace(Dirs.userCacheRoot.toString(), $$"${Dirs.userCacheRoot}")
            .replace(DefaultVersions.kotlin, $$"${DefaultVersions.kotlin}")
            .replace(buildDir.toString(), $$"$buildDir")
            .replace(File.separatorChar, '/')
        assertEqualsIgnoreLineSeparator(
            expectedOutputPath.readText(),
            actualOutputSubstituted,
            expectedOutputPath,
        )
    }

    @Test
    fun `sources injection test`() = runSlowTest {
        val r1 = runCli(
            projectDir = testProject("extensibility/sources"),
            "task", ":app1:consume@consume-sources-plugin",
        )

        val projectDir = r1.projectDir
        val buildDir = tempRoot / "build"
        r1.assertCustomTaskStdoutContains(
            moduleName = "app1",
            taskName = "consume",
            pluginName = "consume-sources-plugin",
            output = """
            Consuming sources: 1
            Got source path: ${projectDir / "app1" / "src"} - [main.kt]
        """.trimIndent()
        )

        runCli(
            projectDir = projectDir,
            "task", ":app2:consume@consume-sources-plugin",
        ).assertCustomTaskStdoutContains(
            moduleName = "app2",
            taskName = "consume",
            pluginName = "consume-sources-plugin",
            output = """
            Consuming sources: 4
            Got source path: ${projectDir / "app2" / "src"} - [main.kt]
            Got source path: ${buildDir / "generated" / "app2" / "main" / "src" / "ksp" / "kotlin"} - [kspGenerated.kt]
            Got source path: ${buildDir / "generated" / "app2" / "main" / "src" / "ksp" / "java"} - []
            Got source path: ${buildDir / "tasks" / "_app2_produceSources@produce-sources-plugin" / "kotlin"} - [generated.kt]
        """.trimIndent()
        )

        runCli(
            projectDir = projectDir,
            "task", ":app3:consume@consume-sources-plugin",
        ).assertCustomTaskStdoutContains(
            moduleName = "app3",
            taskName = "consume",
            pluginName = "consume-sources-plugin",
            output = """
            Consuming sources: 3
            Got source path: ${projectDir / "app3" / "resources"} - [hello]
            Got source path: ${buildDir / "generated" / "app3" / "main" / "resources" / "ksp"} - [com.example.amper.app.Greeter]
            Got source path: ${buildDir / "tasks" / "_app3_produceSources@produce-sources-plugin" / "resources"} - [generated.properties]
        """.trimIndent()
        )

        runCli(
            projectDir = projectDir,
            "task", ":kmp-lib:consume@consume-sources-plugin",
        ).assertCustomTaskStdoutContains(
            moduleName = "kmp-lib",
            taskName = "consume",
            pluginName = "consume-sources-plugin",
            output = """
            Consuming sources: 2
            Got source path: ${projectDir / "kmp-lib" / "src"} - null
            Got source path: ${projectDir / "kmp-lib" / "src@jvm"} - null
        """.trimIndent()
        )

        runCli(
            projectDir = projectDir,
            "task", ":kmp-lib2:consume@consume-sources-plugin",
        ).assertCustomTaskStdoutContains(
            moduleName = "kmp-lib2",
            taskName = "consume",
            pluginName = "consume-sources-plugin",
            output = """
            Consuming sources: 2
            Got source path: ${projectDir / "kmp-lib2" / "resources"} - null
            Got source path: ${projectDir / "kmp-lib2" / "resources@jvm"} - null
        """.trimIndent()
        )
    }

    @Test
    fun `execution avoidance - enabled by default, disabled for no-outputs tasks`() = runSlowTest {
        val r1 = runCli(
            projectDir = testProject("extensibility/single-local-plugin"),
            "task", ":app1:print-generated-sources@build-konfig",
        )

        with(r1) {
            assertStdoutContains("Generating Build Konfig...")
            assertStdoutContains("Printing generated Build Konfig sources...")
        }

        // Incremental re-run, two times
        repeat(2) {
            println("test: run print sources #$it")

            val r2 = runCli(
                projectDir = r1.projectDir,
                "task", ":app1:print-generated-sources@build-konfig"
            )

            with(r2) {
                assertStdoutDoesNotContain("Generating Build Konfig...")
                assertStdoutContains("Printing generated Build Konfig sources...")
            }
        }
    }

    @Test
    fun `execution avoidance - disabled when explicitly opted-out`() = runSlowTest {
        val r1 = runCli(
            projectDir = testProject("extensibility/multiple-local-plugins"),
            "show", "tasks",
        )

        repeat(3) {
            println("test: run print sources #$it")
            val r2 = runCli(
                projectDir = r1.projectDir,
                "task", ":app:print-generated-sources@build-konfig",
            )

            with(r2) {
                assertStdoutContains("Generating Build Konfig...")
            }
        }
    }

    @Test
    fun `inferTaskDependency disabled`() = runSlowTest {
        // 1. check fails at first because the baseline is outdated.
        val r1 = runCli(
            projectDir = testProject("extensibility/multiple-local-plugins"),
            "task", ":app:checkBaseline@build-konfig",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        r1.assertStderrContains("Baseline check failed! Current = {VERSION=1.0, ID=chair-red-dog}, Baseline = {invalid=value}")

        // 1.1 check again - fails because failures are not cached
        runCli(
            projectDir = r1.projectDir,
            "task", ":app:checkBaseline@build-konfig",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        ).assertStderrContains("Baseline check failed!")

        // 2. Update the baseline
        runCli(
            projectDir = r1.projectDir,
            "task", ":app:generate-konfig@build-konfig",
        )

        // 3. Check again - doesn't fail
        runCli(
            projectDir = r1.projectDir,
            "task", ":app:checkBaseline@build-konfig",
        ).assertStdoutContains("Baseline check successful!")
    }

    @Test
    fun `crash inside a task is correctly reported`() = runSlowTest {
        val r1 = runCli(
            projectDir = testProject("extensibility/multiple-local-plugins"),
            "task", ":app:crash@hello",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        with(r1) {
            assertStdoutDoesNotContain("Internal error")
            assertStderrContains(
                """ERROR: Task ':app:crash@hello' failed: java.lang.RuntimeException: Crashing on purpose
                    |        at org.jetbrains.amper.plugins.hello.PluginKt.crash(plugin.kt:28)
                    |Caused by: java.lang.RuntimeException: Nested
                    |        at org.jetbrains.amper.plugins.hello.PluginKt.someFunction(plugin.kt:20)
                    |        at org.jetbrains.amper.plugins.hello.PluginKt.crash(plugin.kt:26)
                    |
                """.trimMargin()
            )
        }
    }

    @Test
    fun `single plugin - no effect when no enabled`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("extensibility/single-local-plugin"),
            "build", "-m", "app2",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        r.assertStderrContains("Unresolved reference 'Konfig'")
        r.assertStdoutContains(
            """
            |    ╭─ WARNING: Plugin `build-konfig` is not enabled, but has some explicit configuration.
            |    ├── Values explicitly set at:
            |    │   ╭─ app3${File.separator}module.yaml:6:5
            |    │   │
            |    │   │     ⌄⌄⌄⌄⌄⌄⌄
            |    │ 6 │     config:
            |    │ 7 │       hello: foo
            |    │ 8 │       bye: bar
            |    │   │ ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    │   ╰─
            |    │   ╭─ app3${File.separator}module.yaml:9:5
            |    │   │
            |    │ 9 │     packageName: com.example
            |    │   │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    │   ╰─
            |    ╰─
            """.trimMargin()
        )
    }

    @Test
    fun `two plugins - enabled`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("extensibility/multiple-local-plugins"),
            "task", ":app:say@hello",
        )
        val slash = r.projectDir.fileSystem.separator

        with(r) {
            assertStdoutContains("Hello!")
            assertStdoutContains("tasks${slash}_app_say@hello")
            assertStdoutContains("multiple-local-plugins${slash}app")
        }

        val r2 = runCli(
            projectDir = r.projectDir,
            "task", ":build-konfig-plugin:say@hello",
        )

        with(r2) {
            assertStdoutContains("Hello!")
            assertStdoutContains("tasks${slash}_build-konfig-plugin_say@hello")
            assertStdoutContains("multiple-local-plugins${slash}build-konfig-plugin")
        }

        val r3 = runCli(
            projectDir = r.projectDir,
            "task", ":hello-plugin:say@hello",
        )

        with(r3) {
            assertStdoutContains("Hello!")
            assertStdoutContains("tasks${slash}_hello-plugin_say@hello")
            assertStdoutContains("multiple-local-plugins${slash}hello-plugin")
        }

        runCli(
            projectDir = r.projectDir,
            "run", "-m", "app",
        )

        assertContains(
            charSequence = (r.projectDir / "app" / "konfig.properties").readText().normalizeLineSeparators(),
            other = """
                ID=chair-red-dog
                VERSION=1.0
            """.trimIndent()
        )
    }

    @Test
    fun `invalid plugins`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/invalid-plugins"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )

        with(result) {
            assertErrors(
                """
                Plugin id must be unique across the project
                ╰─ There are multiple plugins with the id `hello`:
                   ├─ ${projectDir / "plugin-a" / "module.yaml"}:4:7
                   ├─ ${projectDir / "plugin-b" / "module.yaml"}:4:7
                   ╰─ ${projectDir / "hello"}
                """.trimIndent(),
                "${projectDir / "not-a-plugin" / "module.yaml"}:1:10: Unexpected product type for plugin. Expected `jvm/amper-plugin`, got `jvm/app`",
                "${projectDir / "plugin-empty-id" / "module.yaml"}:5:18: Plugin settings class `com.example.Settings` is not found",
                "${projectDir / "invalid-settings" / "module.yaml"}:4:18: Plugin settings class `com.example.Foo` must be an interface annotated with the `@Configurable` annotation",
                "failed to read Kotlin project model, refer to the errors above",
            )
            assertLogContains(
                "Processing local plugin schema for [plugin-empty-id, plugin-no-plugin-block, hello, invalid-settings]...",
                level = Level.INFO
            )
        }
    }

    @Test
    fun `missing plugins`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/missing-plugins"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )

        with(result) {
            val projectYaml = result.projectDir / "project.yaml"
            assertStderrContains("""
            |    ╭─ ERROR: Plugin module `existing-but-not-included` is not included in the project `modules` list
            |    │ → $projectYaml:6:5
            |    │
            |  6 │   - existing-but-not-included
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─
            """.trimMargin())
            assertStderrContains("""
            |    ╭─ ERROR: Plugin module `non-existing` is not found
            |    │ → $projectYaml:7:5
            |    │
            |  7 │   - non-existing
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─
            """.trimMargin())
            // May be changed in the future, beware
            assertNotEquals(
                illegal = true,
                actual = logsDir?.exists(),
                message = "logs dir should not exist when project context parsing is failed"
            )
        }
    }

    @Test
    fun `incomplete plugins`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/incomplete-plugins"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )

        with(result) {
            assertWarnings(
                "${projectDir / "empty-plugin" / "module.yaml"}:2:3: `plugin.yaml` file is missing in the plugins module directory, so it will have no effect when enabled",
                "${projectDir / "no-tasks-plugin" / "plugin.yaml"}: Plugin doesn't register any tasks, so it will have no effect when enabled",
            )
            assertErrors(
                "${projectDir / "app" / "module.yaml"}:5:3: Unknown plugin ID `unknown`. Ensure the corresponding plugin is registered in the `project.yaml` in the `plugins:` list.",
                "${projectDir / "app" / "module.yaml"}:7:5: Unknown property `hello` (inferred type `string`) in `Settings`",
                "${projectDir / "invalid-plugin-yaml-2" / "plugin.yaml"}:3:5: Expected a value: `existingTaskAction {..}`",
                "${projectDir / "invalid-plugin-yaml-2" / "plugin.yaml"}:5:13: Expected a value: `existingTaskAction {..}`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:2:3: Expected a value: `Task {..}`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:5:16: Unexpected custom YAML type tag",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:5:3: Expected a value: `Task {..}`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:6:22: Unexpected custom YAML type tag",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:6:3: Expected a value: `Task {..}`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:8:13: The task action function specifier `com.example.nonExistentTask` doesn't correspond to any available `@TaskAction`-annotated top-level functions. Available task action functions: `<none>`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:10:13: The task action function specifier `com.example.nonExistentTask` doesn't correspond to any available `@TaskAction`-annotated top-level functions. Available task action functions: `<none>`",
                "${projectDir / "invalid-plugin-yaml" / "plugin.yaml"}:4:13: Missing task action function specifier. Add the `!<fully-qualified-task-action-function-name>` YAML type tag to the mapping. Available task action functions: `<none>`",
                "${projectDir / "plugin-deprecated-api" / "plugin.yaml"}:5:5: `markOutputsAs` per-task property is deprecated and no longer has any effect. " +
                        "Use the top-level `generated:` block instead and put your generated output in the corresponding category, e.g., `sources`, `resources`, etc. " +
                        "The actual output path can be referenced from there using the `\${tasks.<your-task-name>.action.<parameter-name>}` syntax.",
                "failed to read Kotlin project model, refer to the errors above",
            )
        }
    }

    @Test
    fun `invalid task graph`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/invalid-task-graph"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )

        val projectDir = result.projectDir
        val pluginYamlForInvalidInputs = projectDir / "tasks-with-invalid-inputs" / "plugin.yaml"
        val pluginYamlForLoops = projectDir / "tasks-with-loops" / "plugin.yaml"
        val sep = File.separatorChar
        result.assertErrors(
            """
            Output path `${projectDir / "app" / "foo" / "bar"}` is declared to be produced by multiple tasks: task `withSameOutputA` in module `app` from plugin `tasks-with-invalid-inputs`, task `withSameOutputB` in module `app` from plugin `tasks-with-invalid-inputs`
            ╰─ The output path is specified at:
               ├─ $pluginYamlForInvalidInputs:22:16
               ╰─ $pluginYamlForInvalidInputs:26:16
            """.trimIndent(),
            """
            Task input/output paths that are children/parents/duplicates of each other are not allowed (reserved for potential future use). The conflicting path is `<project-root-dir>${sep}app`
            ╰─ Conflicting paths are specified at:
               ├─ $pluginYamlForInvalidInputs:5:15
               ├─ $pluginYamlForInvalidInputs:6:15
               ╰─ $pluginYamlForInvalidInputs:7:15
            """.trimIndent(),
            """
            Task input/output paths that are children/parents/duplicates of each other are not allowed (reserved for potential future use). The conflicting path is `<project-root-dir>${sep}app`
            ╰─ Conflicting paths are specified at:
               ├─ $pluginYamlForInvalidInputs:11:15
               ├─ $pluginYamlForInvalidInputs:12:15
               ╰─ $pluginYamlForInvalidInputs:13:15
            """.trimIndent(),
            """
            Task input/output paths that are children/parents/duplicates of each other are not allowed (reserved for potential future use). The conflicting path is `<project-build-dir>${sep}tasks${sep}_app_withConflictingOutputs@tasks-with-invalid-inputs`
            ╰─ Conflicting paths are specified at:
               ├─ $pluginYamlForInvalidInputs:17:16
               ╰─ $pluginYamlForInvalidInputs:18:16
            """.trimIndent(),
            """
            Task dependency loop detected:
            1. task `task3` in module `tasks-with-loops` from plugin `tasks-with-loops` (*)
               ╰───> consumes `<project-build-dir>${sep}tasks${sep}_tasks-with-loops_task2@tasks-with-loops${sep}converted.dat` produced by
            2. task `task2` in module `tasks-with-loops` from plugin `tasks-with-loops` <──────────────────────────────────╯
               ╰───> consumes `<project-build-dir>${sep}tasks${sep}_tasks-with-loops_task1@tasks-with-loops${sep}output.bin` produced by
            3. task `task1` in module `tasks-with-loops` from plugin `tasks-with-loops` <───────────────────────────────╯
               ╰───> consumes `<project-root-dir>${sep}tasks-with-loops${sep}source.txt` produced by ───╮
            4. task `task3` in module `tasks-with-loops` from plugin `tasks-with-loops` (*) <─╯
            ╰─ Related configuration elements that may have caused the loop:
               ├─ $pluginYamlForLoops:10:15
               ├─ $pluginYamlForLoops:6:15
               ╰─ $pluginYamlForLoops:14:15
            """.trimIndent(),
            """
            Task dependency loop detected:
            1. task `taskThatGeneratesSources` in module `tasks-with-loops` from plugin `tasks-with-loops` (*)
               ╰───> depends on the compilation of its source code
            2. compilation of module `tasks-with-loops` <────────╯
               ╰───> needs sources from ─────────────────────────╮
            3. source generation for module `tasks-with-loops` <─╯
               ╰───> includes the directory `<project-build-dir>${sep}tasks${sep}_tasks-with-loops_taskThatGeneratesSources@tasks-with-loops` generated by
            4. task `taskThatGeneratesSources` in module `tasks-with-loops` from plugin `tasks-with-loops` (*) <───────────────────────────────╯
            ╰─ Related configuration elements that may have caused the loop:
               ╰─ $pluginYamlForLoops:22:7
            """.trimIndent(),
            """
            Task dependency loop detected:
            1. task `consumesResources` in module `app` from plugin `tasks-with-invalid-inputs` (*)
               ╰───> requests resources including those generated in
            2. resource generation for module `app` <──────────────╯
               ╰───> includes the directory `<project-build-dir>${sep}tasks${sep}_app_generatesResources@tasks-with-invalid-inputs` generated by
            3. task `generatesResources` in module `app` from plugin `tasks-with-invalid-inputs` <───────────────────────────────────╯
               ╰───> consumes `<project-build-dir>${sep}tasks${sep}_app_consumesResources@tasks-with-invalid-inputs` produced by
            4. task `consumesResources` in module `app` from plugin `tasks-with-invalid-inputs` (*) <────────────────╯
            ╰─ Related configuration elements that may have caused the loop:
               ├─ $pluginYamlForInvalidInputs:38:27
               ├─ $pluginYamlForInvalidInputs:43:7
               ╰─ $pluginYamlForInvalidInputs:39:15
            """.trimIndent(),
            "failed to read Kotlin project model, refer to the errors above",
        )
    }

    @Test
    fun `invalid references`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/invalid-references"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        with(result) {
            val pluginYaml = projectDir / "plugin1" / "plugin.yaml"
            assertErrors(
                "${pluginYaml}:32:5: Cannot assign to property `taskOutputDir` – it is a built-in property available for reference only",
                "${pluginYaml}:18:11: Expected `Dependency.Maven ( maven-coordinates )`, but got `sequence []`",
                "${pluginYaml}:34:1: Cannot assign to property `module` – it is a built-in property available for reference only",
                "${pluginYaml}:17:13: Referencing `markOutputsAs` is not allowed",
                "${pluginYaml}:14:11: Maven coordinates should not contain slashes",
                "${pluginYaml}:15:11: Maven coordinates `one-part` should contain at least two parts separated by `:`, but got `1`",
                "${pluginYaml}:11:18: Referencing `module` is not allowed",
                "${pluginYaml}:12:16: The value of type `mapping {string : Element}` cannot be assigned to the type `Nested`",
                "${pluginYaml}:6:17: The value of type `string` cannot be assigned to the type `boolean`",
                "${pluginYaml}:9:13: The value of type `Settings` cannot be assigned to the type `path`",
                "${pluginYaml}:7:64: The value of type `boolean` cannot be used in string interpolation",
                "${pluginYaml}:4:13: No value for required task action parameters: `int`, `classpath`.",
                "${pluginYaml}:19:93: Unable to find reference's starting element `unknownRoot` in the current context",
                "${pluginYaml}:19:56: Unable to resolve `missing` on a non-object type `string`",
                "${pluginYaml}:19:82: Unable to resolve `unknown`: no such property is found in type `Settings`",
                "${pluginYaml}:10:20: The value of type `path | null` cannot be assigned to the type `integer | null`",
                "${pluginYaml}:20:7: Unknown property `unknownProperty1` (inferred type `string`) in `someAction`",
                "${pluginYaml}:21:7: Unknown property `unknownProperty2` (inferred type `integer`) in `someAction`",
                "${pluginYaml}:22:7: Unknown property `unknownProperty3` (inferred type `integer`) in `someAction`",
                "${pluginYaml}:23:7: Unknown property `unknownProperty4` (inferred type `list [Dependency.Maven]`) in `someAction`",
                "${pluginYaml}:24:7: Unknown property `unknownProperty5` (inferred type `string`) in `someAction`",
                "${pluginYaml}:25:7: Unknown property `unknownProperty6` (inferred type `<undefined-type>`) in `someAction`",
                "${pluginYaml}:25:34: Referencing `settings` is not allowed",
                "${pluginYaml}:26:7: Unknown property `unknownProperty7` (inferred type `KotlinVersion | null`) in `someAction`",
                "${pluginYaml}:28:7: Unknown property `unknownProperty8` (inferred type `path`) in `someAction`",
                "${pluginYaml}:29:7: Unknown property `unknownProperty9` (inferred type `path`) in `someAction`",
                "${pluginYaml}:30:7: Unknown property `unknownProperty10` (inferred type `path`) in `someAction`",
                "${pluginYaml}:31:7: Unknown property `unknownProperty11` (inferred type `string`) in `someAction`",
                "failed to read Kotlin project model, refer to the errors above",
            )
        }
    }

    @Test
    fun `detached plugin - gets diagnosed`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/reference-loops"),
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        with(result) {
            val pluginYaml = projectDir / "loop-plugin" / "plugin.yaml"
            assertErrors(
                """
                Reference loop(s) detected. Please ensure that references do not point to each other or to their own supertree
                ╰─ References forming the loop:
                   ├─ $pluginYaml:5:11
                   ╰─ $pluginYaml:6:11
                """.trimIndent(),
                """
                Reference loop(s) detected. Please ensure that references do not point to each other or to their own supertree
                ╰─ References forming the loop:
                   ├─ $pluginYaml:9:11
                   ├─ $pluginYaml:11:14
                   ╰─ $pluginYaml:12:11
                """.trimIndent(),
                "${pluginYaml}:15:43: Accessing properties/keys on the nullable type `Nested | null` is not allowed.",
                "failed to read Kotlin project model, refer to the errors above",
            )
        }
    }

    @Test
    fun `consistent classloader`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/consistent-classloader"),
            "task", ":app:test@plugin",
        ).assertStdoutContains("Everything is in order")
    }

    @Test
    fun `module settings reference`() {
        val projectDir = testProject("extensibility/settings-reference")
        runSlowTest {
            runCli(
                projectDir = projectDir,
                "task", ":app:check-settings@plugin",
            ).assertCustomTaskStdoutContains(
                moduleName = "app",
                taskName = "check-settings",
                pluginName = "plugin",
                output = """
                    kotlinVersion: 2.2.20
                    kotlinLanguageVersion: 2.2
                    kotlinWarningsAsErrors: true
                    jvmRelease: 17
                    jvmRuntimeClasspathMode: jars
                    jdkVersion: 17
                    junitVersion: junit-4
                    publishingGav: com.example:app:1.0-SNAPSHOT
                    kotlinArgs: [-Xfoo.bar]
                """.trimIndent()
            )

            runCli(
                projectDir = projectDir,
                "task", ":app-default:check-settings@plugin",
            ).assertCustomTaskStdoutContains(
                moduleName = "app-default",
                taskName = "check-settings",
                pluginName = "plugin",
                output = """
                    kotlinVersion: ${DefaultVersions.kotlin}
                    kotlinLanguageVersion: null
                    kotlinWarningsAsErrors: false
                    jvmRelease: ${DefaultVersions.jdk}
                    jvmRuntimeClasspathMode: jars
                    jdkVersion: ${DefaultVersions.jdk}
                    junitVersion: junit-5
                    publishingGav: null:null:null
                    kotlinArgs: null
                """.trimIndent()
            )
        }
    }

    @Test
    fun `plugin invalid references`() = runSlowTest {
        val projectDir = testProject("extensibility/settings-invalid-reference")
        val pluginYaml = projectDir.resolve("plugin-invalid-references/plugin.yaml")
        runCli(
            projectDir = projectDir,
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        ).assertErrors(
            "${pluginYaml}:6:48: Referencing `processors` is not allowed",
            "${pluginYaml}:5:42: Referencing `publishing` is not allowed",
            "${pluginYaml}:4:30: Referencing `settings` is not allowed",
            "failed to read Kotlin project model, refer to the errors above",
        )
    }

    @Test
    fun `plugin is diagnosed on sync when registered to the project but not enabled anywhere`() = runSlowTest {
        val projectDir = testProject("extensibility/pure-plugins-project")
        val pluginYaml = projectDir.resolve("plugin1/plugin.yaml")
        runCli(
            projectDir = projectDir,
            "show", "tasks",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        ).assertErrors(
            "${pluginYaml}:3:13: No value for required task action parameters: `booleanProp`, `intProp`, `enumProp`.",
            "failed to read Kotlin project model, refer to the errors above",
        )
    }

    @Test
    fun `multiple task execution`() = runSlowTest {
        val projectDir = testProject("extensibility/multiple-local-plugins")
        val result = runCli(
            projectDir = projectDir,
            "task", ":app:say@hello", ":app:print-generated-sources@build-konfig",
        )
        result.assertStdoutContains("Hello!")
        result.assertStdoutContains("Generating Build Konfig...")
    }

    @Test
    fun `cascading references`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/cascading-references"),
            "task", ":app:task2@my-plugin",
        )
        val buildDir = tempRoot / "build"
        result.assertStdoutContains("taskAction1: path=${buildDir / "tasks" / "_app_task1@my-plugin" / "file.txt"}, name=test")
        result.assertStdoutContains("taskAction2: path=${buildDir / "tasks" / "_app_task1@my-plugin" / "file.txt"}, name=test")
    }

    @Test
    fun `parametrized dependencies`() = runSlowTest {
        val testProject = testProject("extensibility/parametrized-dependencies")
        val result = runCli(
            projectDir = testProject,
            "task", ":app:build@test-plugin",
        )

        result.assertCustomTaskStdoutEquals(
            expected = testProject / "expected-plugin-output.txt",
            moduleName = "app",
            taskName = "build",
            pluginName = "test-plugin",
        )
    }

    private fun AmperCliResult.assertCustomTaskStdoutContains(
        moduleName: String,
        taskName: String,
        pluginName: String,
        output: String,
    ) = assertContains(extractCustomTaskStdout(moduleName, taskName, pluginName), output)

    private fun AmperCliResult.assertCustomTaskStdoutEquals(
        expected: Path,
        moduleName: String,
        taskName: String,
        pluginName: String,
    ) = assertEqualsIgnoreLineSeparator(
        expected.readText(),
        extractCustomTaskStdout(moduleName, taskName, pluginName),
        expected,
    )

    private fun AmperCliResult.extractCustomTaskStdout(
        moduleName: String,
        taskName: String,
        pluginName: String,
    ): String {
        // the format is defined in TaskFromPlugin.tagForLogs
        val taskOutputLineRegex = """^\[${Regex.escape(moduleName)}]\[${Regex.escape("$taskName@$pluginName")}]\s+(.*)$""".toRegex(RegexOption.MULTILINE)
        return taskOutputLineRegex
            .findAll(stdoutClean)
            .joinToString(separator = "\n") { it.groupValues[1] }
    }

    @Test
    fun `unregistered plugins diagnostics`() = runSlowTest {
        // 1. Assert that no error messages associated with the unregistered plugin are issued just by parsing it.
        val r1 = runCli(
            projectDir = testProject("extensibility/unregistered-plugin-error"),
            "show", "checks",
        )

        // 2.1 Assert that the build triggers plugin analysis.
        val r2 = runCli(
            projectDir = r1.projectDir,
            "build", "-m", "unregistered-plugin",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        val pluginKt = r1.projectDir / "unregistered-plugin" / "src" / "plugin.kt"
        r2.assertErrors(
            "$pluginKt:12:5: Illegal overload for `org.example.myAction`: `@TaskAction` functions can't be overloaded",
            "$pluginKt:9:5: Illegal overload for `org.example.myAction`: `@TaskAction` functions can't be overloaded",
            "Task ':unregistered-plugin:buildAmperPluginInfo' failed: Plugin Kotlin schema processing failed, see the errors above.",
        )
        r2.assertStderrContains("Task ':unregistered-plugin:buildAmperPluginInfo' failed: Plugin Kotlin schema processing failed, see the errors above.")

        // 2.2
        val r3 = runCli(
            projectDir = r1.projectDir,
            "build", "-m", "unregistered-plugin-2",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        val plugin2Yaml = r1.projectDir / "unregistered-plugin-2" / "plugin.yaml"
        r3.assertErrors(
            "$plugin2Yaml:3:13: The task action function specifier `nonExistedType` doesn't correspond to any available `@TaskAction`-annotated top-level functions. Available task action functions: `<none>`",
            "Task ':unregistered-plugin-2:buildAmperPluginInfo' failed: `plugin.yaml` processing failed, see the errors above.",
        )
        r3.assertStderrContains("Task ':unregistered-plugin-2:buildAmperPluginInfo' failed: `plugin.yaml` processing failed, see the errors above.")
    }
}
