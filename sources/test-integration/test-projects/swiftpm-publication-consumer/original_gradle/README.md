# Gradle equivalent of the `swiftpm-publication-consumer` test project

This is a plain Kotlin Multiplatform Gradle project that mirrors the Amper module one directory up: a single `iosArm64`
target, the same sources, and the same single dependency on
`org.jetbrains.kotlintoolchain.swiftpm.sample:swiftPMPublication:1.0.0`, the library published by the
`swiftpm-publication` test project.

Nothing here configures SwiftPM. That is the point: the Kotlin Gradle Plugin discovers the SwiftPM packages of a
dependency on its own, through the `swiftPMDependenciesMetadataElements` variant of that dependency's publication. This
project exists to check that it can do so with a publication produced by the Kotlin Toolchain, which is the direction
the `swiftpm-publication/original_gradle` comparison cannot cover.

It is not built by CI.

## Reproducing the check

```shell
# Publish the library with the Kotlin Toolchain, into a scratch repository so that ~/.m2 stays clean
cd ../../swiftpm-publication
KOTLIN_CLI_JAVA_OPTIONS="-Dmaven.repo.local=/tmp/ktc-swiftpm-m2" kotlin publish mavenLocal

# Consume it with the Kotlin Gradle Plugin
cd ../swiftpm-publication-consumer/original_gradle
./gradlew -Dmaven.repo.local=/tmp/ktc-swiftpm-m2 generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump build
```

## Expected result of the check

KGP consumes the Kotlin Toolchain publication. It resolves the SwiftPM metadata variant, keys it by the sanitized Maven
coordinates of the library, and generates
`build/kotlin/swiftImport/subpackages/org_jetbrains_kotlintoolchain_swiftpm_sample_swiftPMPublication_1_0_0/Package.swift`
holding both packages the library declared:

```swift
  dependencies: [
    .package(path: "../../../../../../../swiftpm-publication/commonPackage"),
    .package(path: "../../../../../../../swiftpm-publication/iosOnlyPackage")
  ],
  targets: [
    .target(
      name: "org_jetbrains_kotlintoolchain_swiftpm_sample_swiftPMPublication_1_0_0",
      dependencies: [
        .product(name: "CommonProduct", package: "commonPackage"),
        .product(
          name: "IosOnlyProduct",
          package: "iosOnlyPackage",
          condition: .when(platforms: [.iOS])
        )
      ]
    )
  ]
```

Both products arrive, and the iOS-only one keeps its platform constraint as a `.when(platforms: [.iOS])` condition, so
the `@ios` fragment qualifier of the publishing Amper module survives the round trip through our metadata. `./gradlew
build` then succeeds on top of that.

Note that `build` compiles the consumer but does not link it, so this check covers the discovery and the shape of the
consumed packages, not the SwiftPM linkage itself. Linkage of the packages inherited from a dependency needs a
framework or an executable binary, which this project does not declare.
