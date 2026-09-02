# Gradle equivalent of the `compose-resources-publication` test project

This is a plain Kotlin Multiplatform + Compose Multiplatform Gradle project that mirrors the Amper modules one
directory up:

| Amper module     | Gradle project    | Platforms                        | `packageName`                |
|------------------|-------------------|----------------------------------|------------------------------|
| `library`        | `:library`        | `jvm`, `wasmJs`                  | `com.example.lib.gen`        |
| `library-native` | `:library-native` | `iosArm64`, `iosSimulatorArm64`  | `com.example.libnative.gen`  |

Both publish `org.example:<name>:1.0.0` to the local Maven repository, and both carry the same Compose resources
(`files/common-text.txt` in common, plus `files/wasm-text.txt` / `files/ios-text.txt` in the platform-specific
fragment).

It is not built by CI. It exists so that the Compose resources publication of the Kotlin Toolchain can be compared
against the one of the Kotlin Gradle Plugin, which is the behaviour Gradle consumers rely on.

## Reproducing the comparison

```shell
# KGP side (use a scratch repository, so that ~/.m2 stays clean)
cd original_gradle
./gradlew -Dmaven.repo.local=/tmp/gradle-m2 publishToMavenLocal

# Kotlin Toolchain side
cd ..
kotlin task :library:prepareMavenPublishables
kotlin task :library-native:prepareMavenPublishables
```

The KGP module metadata is kept for reference in
`sources/test-integration/amper-cli-test/testResources/gradleMetadata/compose_resources_publication_*.original_gradle.json`,
next to the Kotlin Toolchain golden files asserted by `ComposeResourcesTest`.

## Result of the comparison

Everything that is about Compose resources matches:

* The resources are published in a dedicated `<target>ResourcesElements-published` variant, with the very same
  attributes on both sides (`org.gradle.libraryelements` / `org.gradle.usage` are
  `kotlin-multiplatformresourcesjs` for `wasmJs` and `kotlin-multiplatformresources` for the native targets), and the
  same dependency list.
* The `wasmJs` resources variant mirrors the runtime variant, and the native ones mirror the API variant, since
  native targets have no runtime variant. Same on both sides.
* The published archive is `<module>-<target>-<version>-kotlin_resources.kotlin_resources.zip` with the
  `<module>-<version>.kotlin_resources.zip` file name in the metadata, and its entries are identical
  (`composeResources/<packageName>/files/...`, holding the resources as merged for that platform).
* The `jvm` publication has no resources variant on either side: the resources go into the jar, under
  `composeResources/<packageName>/files/`.
* The root module redirects to the target modules for the resources variants too, identically on both sides.

The remaining differences are unrelated to resources, and are the same ones the other `*.original_gradle.json`
reference files in this repository show:

* KGP publishes sources jars, so it has extra `*SourcesElements` variants, and native `*MetadataElements-published`
  variants; the Kotlin Toolchain publishes neither.
* KGP lists `kotlin-stdlib` in the API and metadata variants; the Kotlin Toolchain only lists it in the runtime ones.
* The `files[].name` field (the file name before publication) differs: `library-wasmJsMain-1.0.0.klib` for the
  Kotlin Toolchain vs `library-wasm-js-1.0.0.klib` for KGP. The `url` (what consumers resolve) is the same.
* KGP derives the target module name from the dash-separated target name (`library-wasm-js`), the Kotlin Toolchain
  from the lowercased one (`library-wasmjs`).
* The Kotlin Toolchain adds `artifactType: org.jetbrains.kotlin.klib` to the native API variants.
