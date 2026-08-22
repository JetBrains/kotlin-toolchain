---
description: |
  Migrate your Gradle project to the Kotlin Toolchain: learn how the concepts of a Gradle build — plugins, source
  sets, toolchains, compiler options, and test configuration — translate to the Kotlin Toolchain.
---

# Migrating from Gradle

This guide describes how to convert an existing Gradle project to the Kotlin Toolchain, with details on how specific
parts should be translated.

!!! tip "Have you tried AI?"

    Unlike for [Maven projects](migrating-from-maven.md), there is no deterministic conversion tool for Gradle builds.
    Gradle build scripts are arbitrary code, so a faithful automatic translation isn't possible in general.

    However, AI agents can be quite good for this sort of tasks. We recommend the
    [Kotlin Toolchain skills](https://github.com/singleton11/kotlin-toolchain-skills), which will make your agent handle
    this like a champ!

## Terminology

Here are some Gradle concepts and their Kotlin Toolchain equivalent:

| Gradle                                    | The Kotlin Toolchain                         |
|-------------------------------------------|----------------------------------------------|
| Build                                     | Project                                      |
| Root project                              | Root module                                  |
| Subproject                                | Module                                       |
| Convention plugins (simple configuration) | [Templates](../user-guide/templates.md)      |
| Plugins                                   | [Plugins](../user-guide/plugins/overview.md) |

You can learn more about the basic concepts of the Kotlin Toolchain in the [user guide](../user-guide/basics.md).

## Configuration files at a glance

| Gradle                                                    | The Kotlin Toolchain                                                                                                                     |
|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `settings.gradle(.kts)`                                   | [`project.yaml`](../reference/project.md) — lists the modules of the project                                                             |
| `build.gradle(.kts)` (one per subproject)                 | [`module.yaml`](../reference/module.md) (one per module)                                                                                 |
| `gradle/libs.versions.toml`                               | `gradle/libs.versions.toml` or `libs.versions.toml`<br/>(the [same format](../user-guide/dependencies.md#library-catalogs) is supported) |
| `gradlew` / `gradlew.bat`<br/>`gradle-wrapper.properties` | The [`kotlin` wrapper scripts](../cli/provisioning.md)                                                                                   |
| `gradle.properties`                                       | No direct equivalent, all settings are in `module.yaml`                                                                                  |

## Conversion

### General workflow 

Here is how the general workflow looks like:

1. Create a `project.yaml` file listing your modules, based on the `include(...)` calls in `settings.gradle(.kts)`.
2. For each convention plugin in `buildSrc` or `build-logic`, create a template file `<name>.module-template.yaml`.
   You can convert it the same way as a `build.gradle.kts` to `module.yaml` file, see below.
3. For each more complex local plugin, either remove it (if it became redundant) or convert it to a [local Kotlin Toolchain plugin](../user-guide/plugins/overview.md)
4. For each Gradle subproject, create a `module.yaml` file, translating the build script using the sections below.
5. Move the source files to the [standard layout](../user-guide/basics.md#project-layout)
   (or use the [Maven-like layout](../user-guide/advanced/maven-like-layout.md) for JVM-only modules).
6. Run `./kotlin build` and `./kotlin test` to verify the migration.
7. Remove the Gradle build files.
8. Move your `libs.versions.toml` (if you have one) to the root of the project

### Project structure

The `include(...)` calls from `settings.gradle(.kts)` become the `modules` list in `project.yaml`.
Gradle project paths use `:` as separator, while the Kotlin Toolchain uses real directory paths:

<div class="grid" markdown>

```kotlin title="settings.gradle.kts"
rootProject.name = "my-project"

include(":app")
include(":libs:lib1")
```

```yaml title="project.yaml"
modules:
  - app
  - libs/lib1
‎
```

</div>

!!! note "Single-module projects don't need a `project.yaml` file at all — a `module.yaml` alone is a valid project."

### Converting a subproject

The goal is to turn the `build.gradle(.kts)` build script into a `module.yaml` file.
These guidelines also apply to converting a convention plugin into a template file.

#### Choose a product type

The Kotlin Toolchain has the concept of [product type](../user-guide/basics.md#product-type), which specifies what a
given module is meant to produce. This has no direct Gradle equivalent. In Gradle, this is understood through the set of
plugins you apply and the configuration in general.

| Gradle plugins                  | Kotlin Toolchain product type |
|---------------------------------|-------------------------------|
| `kotlin("multiplatform")`       | `kmp/lib`                     |
| `kotlin("jvm")`                 | `jvm/lib`                     |
| `kotlin("jvm")` + `application` | `jvm/app`                     |

!!! question "No multiplatform app product type?"

    The Kotlin Toolchain doesn't allow building different application types from the same module.
    This should not occur in modern Gradle setup either, but it is possible.
    If you are in this situation, use `kmp/lib` as a product type for such a module and keep all your source code there.
    Then add more modules with application product types for each platform, and make them depend on this shared code.

#### Kotlin version

In Gradle, Kotlin support comes from the Kotlin Gradle plugin (KGP), which you apply and version explicitly in every
build script or convention plugin. In the Kotlin Toolchain, Kotlin support is built-in.

The Kotlin version doesn't have to be specified: a default Kotlin version will be used.
Note that this default is bumped on each Kotlin Toolchain release, so relying on the default can break your builds on 
update. It is recommended to pin the version.

Example:

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm") version "2.4.10"
}
‎
‎
```

```yaml title="module.yaml"
product: jvm/lib

settings:
  kotlin:
    version: 2.4.10 #(1)! 
```

1. Even though this may match the default version at the time of writing, it's usually more robust to specify the 
   Kotlin version explicitly, to avoid breaking things when bumping the Kotlin Toolchain.

</div>

!!! tip "Tip: use a [template](../user-guide/templates.md) to share the Kotlin version setting."

#### Kotlin platforms

If your module is multiplatform, set the `product.platforms` list to same list of targets as in Gradle's `kotlin { ... }` block:

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
plugins {
    kotlin("multiplatform") version "2.4.10"
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
}
```

```yaml title="module.yaml"
product:
  type: kmp/lib
  platforms: [ jvm, iosArm64, iosSimulatorArm64 ]

settings:
  kotlin:
    version: 2.4.10
```

</div>

#### JVM application main class

For [JVM applications](../user-guide/product-types/jvm-app.md), the **main class** is auto-detected if the `main` function
is in a file named `main.kt`. Otherwise, you have to set it explicitly with `settings.jvm.mainClass`.

Example (using the `maint.kt` convention):

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm") version "2.4.10"
    application
}

application {
    mainClass = "com.example.MainKt"
}
```

```yaml title="module.yaml"
product: jvm/app

settings:
  kotlin:
    version: 2.4.10

# auto-detected main class in main.kt
‎
```

</div>

Example (explicit main class):

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm") version "2.4.10"
    application
}

application {
    mainClass = "com.example.Foo"
}
```

```yaml title="module.yaml"
product: jvm/app

settings:
  jvm:
    mainClass: com.example.Foo
  kotlin:
    version: 2.4.10
‎
```

</div>

#### Source sets and file layout

Gradle organizes code into _source sets_. The Kotlin Toolchain instead uses conventional directories in each module,
without per-language subdirectories (Kotlin and Java files live side by side).

For a JVM module, the mapping is:

| Gradle (Kotlin JVM)   | The Kotlin Toolchain |
|-----------------------|----------------------|
| `src/main/kotlin/`    | `src/`               |
| `src/main/java/`      | `src/`               |
| `src/main/resources/` | `resources/`         |
| `src/test/kotlin/`    | `test/`              |
| `src/test/resources/` | `testResources/`     |

For a multiplatform module, each Kotlin source set maps to a source directory with an
[`@platform` qualifier](../user-guide/multiplatform.md#platform-qualifier):

| Gradle source set        | The Kotlin Toolchain |
|--------------------------|----------------------|
| `commonMain/kotlin/`     | `src/`               |
| `jvmMain/kotlin/`        | `src@jvm/`           |
| `iosMain/kotlin/`        | `src@ios/`           |
| `iosArm64Main/kotlin/`   | `src@iosArm64/`      |
| `commonMain/resources/`  | `resources/`         |
| `androidMain/resources/` | `resources@android/` |
| `commonTest/kotlin/`     | `test/`              |
| `jvmTest/kotlin/`        | `test@jvm/`          |

The `@platform` qualifiers follow the same [default hierarchy](../user-guide/multiplatform.md#supported-platforms)
as KGP's default hierarchy template, with the same visibility rules as `dependsOn` relations between source sets:
code in `src@ios` sees declarations from `src`, `src@native`, and `src@apple`, and is shared between all iOS targets.
If you defined custom intermediate source sets in Gradle (custom `dependsOn` edges), use
[aliases](../user-guide/multiplatform.md#aliases) instead:

```yaml title="module.yaml"
aliases:
  - jvmAndAndroid: [ jvm, android ] # enables src@jvmAndAndroid, dependencies@jvmAndAndroid, etc.
```

Per-source-set dependencies map to qualified dependency sections: the `commonMain` dependencies go to
`dependencies:`, the `jvmMain` ones to `dependencies@jvm:`, and the `commonTest` ones to `test-dependencies:`.

!!! tip "JVM-only modules can avoid moving files"

    Gradle's default JVM layout is the same as Maven's, so `jvm/app` and `jvm/lib` modules can add
    `layout: maven-like` to their `module.yaml` and keep the existing `src/main/kotlin`, `src/test/kotlin`, etc.
    directories as-is. See [Maven-like layout](../user-guide/advanced/maven-like-layout.md).
    There is no such option for multiplatform modules or custom `srcDir` configurations — those files need to move.

#### Built-in frameworks and technologies

Some popular frameworks or technologies that require a Gradle plugin have a built-in equivalent in the Kotlin Toolchain:

* [Compose Multiplatform](../user-guide/builtin-tech/compose-multiplatform.md)
* [kotlinx.serialization](../user-guide/builtin-tech/kotlinx-serialization.md)
* [kotlinx.rpc](../user-guide/builtin-tech/kotlinx-rpc.md)
* [KSP](../user-guide/advanced/ksp.md)
* [Ktor](../user-guide/builtin-tech/ktor.md)
* [Spring](../user-guide/builtin-tech/spring.md)
* [Lombok](../user-guide/builtin-tech/lombok.md)

Check out the relevant sections to see how they are configured in the Kotlin Toolchain.

Kotlin compiler plugins are also directly supported in the `setting.kotlin` section and don't require a plugin.
See the [compiler plugins section](../user-guide/advanced/kotlin-compiler-plugins.md) of user guide to learn how to use them.

#### Dependencies

Gradle dependency configurations map to dependency [scopes and visibility attributes](../user-guide/dependencies.md#transitivity-and-scope):

| Gradle                                     | The Kotlin Toolchain                     | Config section      |
|--------------------------------------------|-------------------------------------------|---------------------|
| `implementation("group:artifact:1.0")`     | `- group:artifact:1.0`                    | `dependencies`      |
| `api("group:artifact:1.0")`                | `- group:artifact:1.0: exported`          | `dependencies`      |
| `compileOnly("group:artifact:1.0")`        | `- group:artifact:1.0: compile-only`      | `dependencies`      |
| `runtimeOnly("group:artifact:1.0")`        | `- group:artifact:1.0: runtime-only`      | `dependencies`      |
| `implementation(project(":libs:lib1"))`    | `- //libs/lib1`                           | `dependencies`      |
| `implementation(platform("g:bom:1.0"))`    | `- bom: g:bom:1.0`                        | `dependencies`      |
| `implementation(libs.ktor.client.core)`    | `- $libs.ktor.client.core`                | `dependencies`      |
| `testImplementation("group:artifact:1.0")` | `- group:artifact:1.0`                    | `test-dependencies` |

As with Gradle's `implementation`, dependencies are not part of the module's compile-time API by default:
mark a dependency as `exported` where you used `api(...)`.

If you use a [Gradle version catalog](https://docs.gradle.org/current/userguide/version_catalogs.html), you can keep
your `gradle/libs.versions.toml` file as-is (or move it to the project root): the Kotlin Toolchain reads the same
format and exposes it as the `$libs` catalog. Only the `[versions]` and `[libraries]` sections are used —
`[plugins]` doesn't apply, and `[bundles]` is not supported.
See [Library catalogs](../user-guide/dependencies.md#library-catalogs).

The `repositories { ... }` block maps to the `repositories:` list. Maven Central and Google's Maven repository are
configured by default, so most projects don't need this section at all.
See [Managing Maven repositories](../user-guide/dependencies.md#managing-maven-repositories).

#### JDK provisioning

Gradle can provision JDKs through [Java toolchains](https://docs.gradle.org/current/userguide/toolchains.html), which
requires a toolchain resolver plugin (usually `foojay-resolver-convention`) in the settings script. The Kotlin
Toolchain provisions JDKs out of the box — no plugin required, and no JDK needs to be pre-installed on the machine:

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.AZUL
    }
}
```

```yaml title="module.yaml"
settings:
  jvm:
    jdk:
      version: 17
      distributions: [ zulu ] # optional
```

</div>

If nothing is configured, the Kotlin Toolchain uses a default JDK version (currently 25) from any distribution.
By default, it uses the JDK from `JAVA_HOME` if it matches the requirements, and downloads a matching JDK otherwise —
like Gradle's toolchain auto-detection and auto-download. This behavior can be adjusted with
[`settings.jvm.jdk.selectionMode`](../user-guide/advanced/jdk-provisioning.md#jdk-selection-mode):
use `javaHome` to forbid downloads (similar to `org.gradle.java.installations.auto-download=false`), or
`alwaysProvision` to ignore `JAVA_HOME` entirely for more reproducible builds.

Read more on the [JDK provisioning](../user-guide/advanced/jdk-provisioning.md) page.

#### JVM source/target/release

In Gradle, keeping the bytecode compatibility consistent requires aligning several options:
`sourceCompatibility`/`targetCompatibility` or `options.release` for `javac`, and `jvmTarget` for the Kotlin
compiler. The Kotlin Toolchain replaces all of these with the single
[`settings.jvm.release`](../reference/module.md#settingsjvm) setting, which behaves like `javac`'s `--release`
option but applies to both compilers: it sets the target bytecode version for Kotlin and Java, restricts the
available JDK APIs, and limits the Java language constructs.

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
```

```yaml title="module.yaml"
settings:
  jvm:
    jdk:
      version: 21 # compile using JDK 21...
    release: 17   # ...for code that must run on Java 17+
```

</div>

If `release` is not set, it defaults to the JDK version, so a plain module compiled with the default JDK 25 targets
Java 25.

Relatedly, `-parameters` (or KGP's `javaParameters`) is replaced by `settings.jvm.storeParameterNames: true`, which
covers both compilers at once.

#### Compiler arguments

KGP's `compilerOptions { ... }` block maps to [`settings.kotlin`](../reference/module.md#settingskotlin).
Common options have dedicated settings, and anything else can be passed via `freeCompilerArgs`:

```kotlin title="build.gradle.kts"
kotlin {
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_3
        allWarningsAsErrors = true
        progressiveMode = true
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
```

```yaml title="module.yaml"
settings:
  kotlin:
    languageVersion: 2.3
    allWarningsAsErrors: true
    progressiveMode: true
    optIns: [ kotlin.time.ExperimentalTime ]
    freeCompilerArgs: [ -Xcontext-parameters ]
```

Java compiler arguments (`options.compilerArgs`) map to `settings.java.freeCompilerArgs` in the same way.

Where you configured Kotlin compile tasks of a specific target in Gradle, use
[`@platform`-qualified settings](../user-guide/multiplatform.md#multiplatform-settings) instead, e.g.
`settings@jvm:`. Options that only apply to test compilations go into the `test-settings:` section.
Platform-specific and test settings are merged with the common ones according to the
[propagation rules](../user-guide/multiplatform.md#dependencysettings-propagation).

#### JUnit Platform by default

In Gradle, running JUnit 5 tests requires opting in with `useJUnitPlatform()` and adding the test framework
dependencies. In the Kotlin Toolchain, the JUnit Platform is enabled out of the box on JVM and Android:
[`settings.junit`](../reference/module.md#settingsjunit) defaults to `junit-5`, and the 
[`kotlin-test`](https://kotlinlang.org/api/latest/kotlin.test/) framework is preconfigured for each platform.
For a typical module, no test configuration is needed at all — put your tests in `test/` and run `./kotlin test`.

The test-task configuration from Gradle maps to [`settings.jvm.test`](../reference/module.md#settingsjvmtest):

<div class="grid" markdown>

```kotlin title="build.gradle.kts"
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.3")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx2g")
    systemProperty("java.awt.headless", "true")
    environment("MY_ENV_VAR", "value")
}
```

```yaml title="module.yaml"
test-dependencies:
  - io.mockk:mockk:1.14.3

settings:
  jvm:
    test:
      freeJvmArgs: [ -Xmx2g ]
      systemProperties:
        java.awt.headless: true
      extraEnvironment:
        MY_ENV_VAR: value
```

</div>

Projects still on JUnit 4 can set `settings.junit: junit-4`, and `settings.junit: none` disables the automatic JUnit
setup entirely. Read more on the [Testing](../user-guide/testing.md) page.

## Sharing build logic

Configuration that Gradle projects share via convention plugins, `buildSrc`, or `subprojects { ... }` blocks is
shared using [templates](../user-guide/templates.md): files named `<name>.module-template.yaml` with the same
structure as `module.yaml`, applied where needed:

<div class="grid" markdown>

```yaml title="common.module-template.yaml"
settings:
  kotlin:
    version: 2.3.20
    allWarningsAsErrors: true
  jvm:
    release: 17
```

```yaml title="app/module.yaml"
product: jvm/app

apply:
  - //common.module-template.yaml
```

</div>

For build logic that goes beyond configuration — custom tasks, code generation, verification — see
[Kotlin Toolchain plugins](../user-guide/plugins/overview.md).

## Everyday commands

| Gradle                            | The Kotlin Toolchain                |
|-----------------------------------|-------------------------------------|
| `./gradlew build`                 | `./kotlin build`                    |
| `./gradlew test`                  | `./kotlin test`                     |
| `./gradlew :app:test`             | `./kotlin test -m app`              |
| `./gradlew run`                   | `./kotlin run`                      |
| `./gradlew :app:dependencies`     | `./kotlin show dependencies -m app` |
| `./gradlew tasks`                 | `./kotlin show tasks`               |
| `./gradlew publishToMavenLocal`   | `./kotlin publish mavenLocal`[^1]   |
| `./gradlew clean`                 | `./kotlin clean`                    |

[^1]: After setting up the [publishing configuration](../user-guide/publishing.md).

Like `gradlew`, the `kotlin` wrapper scripts are meant to be committed to your repository, and download everything
they need on first use. See [Wrapper & provisioning](../cli/provisioning.md).
