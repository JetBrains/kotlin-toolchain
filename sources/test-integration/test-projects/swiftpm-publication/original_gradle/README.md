# Gradle equivalent of the `swiftpm-publication` test project

This is a plain Kotlin Multiplatform Gradle project that mirrors the Amper module one directory up: same targets
(`iosArm64`, `macosArm64`), same sources, same coordinates
(`org.jetbrains.kotlintoolchain.swiftpm.sample:swiftPMPublication:1.0.0`), and the very same two local Swift packages,
referenced from `../commonPackage` and `../iosOnlyPackage` so that both publications describe identical packages.

One thing cannot be mirrored literally: the SwiftPM DSL of the Kotlin Gradle Plugin is per-project, so it has no
equivalent of the Amper module's `dependencies@ios`. The iOS-only scope is expressed here as an explicit platform
constraint on the product (`product("IosOnlyProduct", platforms = setOf(iOS()))`) instead, which is the same thing the
Kotlin Toolchain derives from the `@ios` fragment qualifier.

It is not built by CI. It exists so that the SwiftPM metadata publication of the Kotlin Toolchain can be compared
against the one of the Kotlin Gradle Plugin, which is the behaviour Gradle consumers rely on.

## Reproducing the comparison

```shell
# KGP side (use a scratch repository, so that ~/.m2 stays clean)
cd original_gradle
./gradlew -Dmaven.repo.local=/tmp/kgp-swiftpm-m2 publishToMavenLocal

# Kotlin Toolchain side
cd ..
KOTLIN_CLI_JAVA_OPTIONS="-Dmaven.repo.local=/tmp/ktc-swiftpm-m2" kotlin publish mavenLocal
```

The KGP metadata is kept for reference in
`sources/test-integration/amper-cli-test/testResources/gradleMetadata/swiftpm_metadata_is_published_in_a_dedicated_variant.*.original_gradle.json`,
right next to the golden files that `SwiftPMPublicationTest` asserts the Kotlin Toolchain publication against, so the
two can be read side by side. The `*.original_gradle.json` ones are references only, nothing is asserted against them.

They hold KGP's output as it comes, like the other `*.original_gradle.json` files in that directory (the SwiftPM
metadata is only pretty-printed, because KGP writes it on a single line). The golden files next to them went through
`sanitizedGradleMetadata` / `sanitizedSwiftPMMetadata` instead, so a few lines differ for reasons that have nothing to
do with the publications: checksums and file sizes read `mocked` and `-1`, and the absolute path of every local Swift
package is replaced by `<project-dir>` (both sides really do publish an absolute path there, but the Kotlin Toolchain
one points into the temporary copy of the test project that the test runs on).

## Expected result of the comparison

Everything that is about SwiftPM matches.

The `swiftPMDependenciesMetadataElements` variant of the root publication is **identical** on both sides:

* same variant name,
* same attributes, and only those two: `org.gradle.category: library` and
  `org.gradle.usage: swiftPMDependenciesMetadata`,
* same single file, with the `swiftPMDependenciesMetadata` name and the
  `swiftPMPublication-1.0.0-swiftpm-metadata.json` URL that consumers resolve.

The published `swiftpm-metadata.json` matches too: same top-level keys, same `konanTargets` (as a set, see below), the
four deployment version keys present and `null` on both sides, `isModulesDiscoveryEnabled: true`, and for every package
the same `type` discriminator, `absolutePath`, `packageName`, `traits`, and product platform constraints
(`null` for the common package, `["iOS"]` for the iOS-only one).

The remaining differences:

* **The Clang module lists are empty in the Kotlin Toolchain publication.** KGP derives one Clang module per declared
  product, so it publishes `cinteropClangModules: [{"name": "CommonProduct", "platformConstraints": null}]` on the
  package (and `["IosOnlyProduct"]` on the constrained product), where the Kotlin Toolchain publishes `[]` in both
  places. This is harmless as long as module discovery is on, which is what both sides publish
  (`isModulesDiscoveryEnabled: true`) and what KGP defaults to: in that mode KGP documents that "explicit module lists
  configured through `swiftPackage`, `localSwiftPackage`, or `product` are not used for cinterop module selection". A
  consumer that turns discovery off would however find no module to import.
* `konanTargets` holds the same targets in a different order (`["macos_arm64", "ios_arm64"]` versus
  `["ios_arm64", "macos_arm64"]`): neither side sorts, so each just reflects its own target order, and consumers read
  the array into a set. Cosmetic.
* The Kotlin Toolchain publishes `sha1` and `md5` checksums for the metadata file, KGP also publishes `sha256` and
  `sha512`. This is the general checksum behaviour of the two publications, not specific to SwiftPM.

The other differences in the root module are the same ones the other `*.original_gradle.json` reference files in this
repository show, and are unrelated to SwiftPM: KGP publishes sources jars and native `*MetadataElements-published`
variants, the Kotlin Toolchain publishes neither.
