# Gradle equivalent of the `compose-resources-publication-consumer` test project

This is a plain Kotlin Multiplatform + Compose Multiplatform Gradle project that mirrors the Amper modules one
directory up:

| Amper module | Gradle project | Platforms                       | `packageName`               | Depends on                       |
|--------------|----------------|---------------------------------|-----------------------------|----------------------------------|
| `app`        | `:app`         | `wasmJs`                        | `com.example.app.gen`       | `org.example:library:1.0.0`      |
| `app-ios`    | `:app-ios`     | `iosArm64`, `iosSimulatorArm64` | `com.example.appios.gen`    | `org.example:library-native:1.0.0` |

It is not built by CI. It exists to check that the Compose resources published by the Kotlin Toolchain for the
`compose-resources-publication` test project are consumable by a Gradle build, which is the whole point of publishing
them in a dedicated Gradle metadata variant.

`:app-ios` only covers the Kotlin framework: the Amper `ios/app` product also builds the Xcode app around it, which
in a Gradle setup lives outside the build (an Xcode project calling `embedAndSignAppleFrameworkForXcode` and
`syncComposeResourcesForIos`). Only the resources matter here, so that shell is left out.

## Reproducing the check

```shell
# Publish the libraries with the Kotlin Toolchain, into a scratch repository so that ~/.m2 stays clean
cd ../../compose-resources-publication
KOTLIN_CLI_JAVA_OPTIONS="-Dmaven.repo.local=/tmp/ktc-m2" kotlin publish mavenLocal

cd ../compose-resources-publication-consumer/original_gradle

# wasmJs: the distribution must hold the resources of the library
./gradlew -Dmaven.repo.local=/tmp/ktc-m2 :app:wasmJsBrowserDistribution

# iOS: 'syncComposeResourcesForIos' is what Xcode calls, so it needs Xcode's environment variables
BUILT_PRODUCTS_DIR=/tmp/ios-bundle UNLOCALIZED_RESOURCES_FOLDER_PATH=app-ios.app \
  CONFIGURATION=Debug PLATFORM_NAME=iphoneos ARCHS=arm64 KOTLIN_FRAMEWORK_BUILD_TYPE=debug \
  ./gradlew -Dmaven.repo.local=/tmp/ktc-m2 :app-ios:syncComposeResourcesForIos
```

## Result of the check

Both consumers resolve the Kotlin Toolchain publication and pick up its resources, matching what the Amper consumer
gets in `ComposeResourcesTest`:

* `:app` resolves `org.example:library:1.0.0` down to `org.example:library-wasmjs:1.0.0` (the module name the Kotlin
  Toolchain publishes the target under) and unpacks its KMP resources archive into
  `app/build/dist/wasmJs/productionExecutable/composeResources/com.example.lib.gen/files/`, with both
  `common-text.txt` and `wasm-text.txt`.
* `:app-ios` resolves `org.example:library-native:1.0.0` for `iosArm64` and puts its resources next to its own ones
  under `<bundle>/compose-resources/composeResources/`: `com.example.libnative.gen/files/{common-text,ios-text}.txt`
  for the library, `com.example.appios.gen/files/app-text.txt` for the app.
