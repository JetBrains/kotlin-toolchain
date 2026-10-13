---
description: This page describes how tests work in the Kotlin Toolchain.
---
# Testing

Test code is located in the `test/` folder:

```
├─ src/            # production code
├─ test/           # test code
│  ├─ MainTest.kt
│  ╰─ ...
├─ testResources/  # test resources
╰─ module.yaml
```

Resources that should only be available to test code go into the `testResources/` folder.
In multiplatform modules, platform-specific test resources can be placed in `testResources@platform/` folders,
just like `src@platform/` folders for sources.

By default, the [Kotlin test](https://kotlinlang.org/api/latest/kotlin.test/) framework is preconfigured for each
platform.

!!! note "JUnit version"

    On the JVM and Android platforms, tests run with JUnit, and JUnit 5 is used by default.
    You can change this with the `settings.junit` setting, which accepts `junit-5`, `junit-4`, or `none`.
    This setting also selects which flavor of the Kotlin test library is added automatically:
    `kotlin-test-junit5`, `kotlin-test-junit`, or just `kotlin-test`, respectively.
    See the [`settings.junit` reference](../reference/module.md#settingsjunit) for more details.

Additional test-only dependencies should be added to the `test-dependencies:` section of your module
configuration file:

```yaml title="module.yaml"
product: jvm/app

# these dependencies are available in main and test code
dependencies:
  - io.ktor:ktor-client-core:2.2.0

# additional dependencies for test code
test-dependencies:
  - io.ktor:ktor-server-test-host:2.2.0
```

To add or override [toolchain settings](basics.md#settings) in tests, use the `test-settings:` section:
```yaml title="module.yaml"
# these dependencies are available in main and test code
settings:
  kotlin:
    ...

# additional test-specific setting 
test-settings:
  kotlin:
    ...
```

Test settings and dependencies by default are inherited from the main configuration according to the 
[configuration propagation rules](multiplatform.md#dependencysettings-propagation).
Example:
```
├─ src/
├─ src@ios/
├─ test/           # Sees declarations from src/. Executed on all platforms.
│  ├─ MainTest.kt
│  ╰─ ...
├─ test@ios/       # Sees declarations from src/, src@ios/, and `test/`. Executed on iOS platforms only.
│  ├─ IOSTest.kt
│  ╰─ ...
╰─ module.yaml
```

```yaml title="module.yaml"
product:
  type: kmp/lib
  platforms: [android, iosArm64]

# these dependencies are available in main and test code
dependencies:
  - io.ktor:ktor-client-core:2.2.0

# dependencies for test code
test-dependencies:
  - org.jetbrains.kotlin:kotlin-test:1.8.10
  
# these settings affect the main and test code
settings: 
  kotlin:
    languageVersion: 2.1

# these settings affect tests only
test-settings:
  kotlin:
    languageVersion: 2.2 # overrides settings.kotlin.languageVersion 2.1
```

## Test reports

JVM, Android, and Wasm browser tests write JUnit XML reports under `build/reports/`.
Use `--reports-dir` to choose a different root directory, independently of the console output format:

```shell
./kotlin test --reports-dir="ci-reports" --format=teamcity
```

Relative report paths are resolved from the current working directory. The default report root follows `--build-dir`.
JVM and Android reports are grouped by module and platform. Wasm browser reports also separate build variants:

```text
ci-reports/<module>/wasmJs/debug/TEST-browser.xml
```

For example, a CI report collector can read `ci-reports/**/TEST-*.xml`. Collect reports even when the test command fails.
The XML records successful, failed, and ignored tests, durations, failure details, and captured output.
The `--format` option still controls only console output. File reporting observes the same test events and does not
change which tests run. A report-writing error fails the command; if the test run also failed, its original error is retained.

A completed Wasm browser run with no reported tests writes a report with zero tests. Ignored tests count as skipped
even when no tests execute; the report follows the events emitted by the test adapter. Tasks skipped because
they have no sources or because tag filters exclude Wasm tests do not write a report. Each executed browser test task
removes its previous report first, so these skipped runs cannot leave an old success report for that task.
Reports for other modules and variants are preserved.

Browser timeouts, page errors, and coroutine cancellation preserve completed results and mark unfinished tests as errors.
If the runner fails while no test is running, the report contains a synthetic `Test runner` error case.
Reports are finalized during normal task cleanup; forcibly terminating the process cannot guarantee a final report.

Wasm reports currently use the existing Kotlin test adapter. Mocha migration, CTRF, and Open Test Reporting support
are separate follow-up work.
