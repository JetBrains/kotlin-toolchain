---
description: Learn how to use the `jvm/lib` product type in a module to build a JVM library.
---
# :intellij-java: JVM library

Use the `jvm/lib` product type in a module to build a JVM library.

## Module layout

Here is an overview of the module layout for a JVM library:

```shell
my-module/
├─ resources/ # (1)!
│  ╰─ logback.xml # (2)!
├─ src/
│  ├─ lib.kt
│  ╰─ Util.java # (3)!
├─ test/
│  ╰─ LibTest.java # (4)!
│  ╰─ UtilTest.kt
├─ testResources/
│  ╰─ logback-test.xml # (5)!
╰─ module.yaml
```

1. Resources placed here are copied into the resulting JAR.
2. This is just an example resource and can be omitted.
3. You can mix Kotlin and Java source files in a single module, all in the `src` folder.
4. You can test Java code with Kotlin tests or Kotlin code with Java tests.
5. This is just an example resource and can be omitted.

!!! note "Maven compatibility layout for JVM-only modules"

    If you're migrating from Maven, you can also configure the [Maven-like layout](../advanced/maven-like-layout.md)

## Packaging

The `kotlin package` command isn't defined _by default_ for JVM libraries.

If [publishing](../publishing.md) to Maven Central is enabled, then `kotlin package` creates a Maven Central ZIP bundle
that is ready to be uploaded to the Central Portal.
More specifically, enabling Maven Central publication provides the `maven-central-bundle` packaging format, and because
it's the only one, it means the `kotlin package` command is effectively `kotlin package --format=maven-central-bundle`.

## Publishing

The `kotlin publish <repository>` command can be used to publish the library to a Maven repository.
Read more about this in the [publishing](../publishing.md) guide.