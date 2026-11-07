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

## Running tests

Run the tests in the project with the `kotlin test` command:

```shell
kotlin test
```

To run tests from specific modules, pass `-m` (or `--include-module`).
Repeat the option to include several modules.
Pass `--exclude-module` to skip modules:

```shell
kotlin test -m my-module
```

Run `kotlin test --help` for the full list of options.

Kotlin/Wasm browser tests are built and executed in a headless browser.
See [Kotlin/Wasm testing](product-types/wasm-js-app.md#testing) for how that run works.

### Selecting tests

To run a specific test, pass `--include-test` with the fully qualified method name, including the package and the
containing class or top-level suite.
Repeat the option to run several tests.
If `--include-classes` is also set, that test is run in addition to the tests matched by the class patterns.

```shell
kotlin test -m my-module --include-test com.example.UtilTest.myTest
```

For a test in a nested class, separate the outer class from the nested class with `/`, for example
`com.example.MyTest/MyNested.myTest`.
A parameter type list may follow the method name, as in `com.example.MyTest.myTest(com.example.Param)`.

To select test classes or suites, use `--include-classes` and `--exclude-classes`.
Each value is the fully qualified name of a test class or top-level test suite function, and may contain `*` to match
any sequence of characters or `?` to match a single character.
Wildcards match across `.` and `/` separators.
Nested classes use the `/` separator.
Repeat an option to match any of the given patterns.
When both options are set, a class is run only if it matches an include pattern and does not match an exclude pattern.

```shell
kotlin test -m my-module --include-classes 'com.example.*Test'
```

!!! tip "Quote patterns to prevent the shell from expanding them"

    Shells expand characters such as `*` and `?` before they reach the Kotlin CLI.
    Quote each pattern, as in the example above.

In a project with more than one module, class patterns require a module filter
(`-m`/`--include-module` or `--exclude-module`) naming the modules where matching tests are expected.
Including a module that has no matching tests results in an error.
Other test filters, including `--include-test`, have the same requirement.
This is a limitation of the current implementation that we intend to fix as part of
[KTC-4234](https://youtrack.jetbrains.com/issue/KTC-4234).

On Kotlin/Wasm, these patterns support `*` but not `?`.
A pattern that starts with an uppercase letter is also rejected.
Prefix it with `*` when matching other prefixes is acceptable.

### Test reports

On the JVM and Android, the test run writes JUnit reports under `build/reports/<module-name>/<platform>/`.
Kotlin/Native, iOS, and Kotlin/Wasm runs print results to the console and do not write report files.
