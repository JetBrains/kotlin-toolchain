---
description: Learn how to use the `ios/app` product type in a module to build an iOS application.
---
# :simple-apple: iOS application

Use the `ios/app` product type in a module to build an iOS application.

!!! tip "Using IntelliJ IDEA?"

    Make sure to install the 
    [:jetbrains-kotlin-multiplatform: Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform)
    to get proper support for Kotlin/Native and iOS.

## Module layout

Here is an overview of the module layout for an iOS application:

--8<-- "includes/module-layouts/ios-app.md"

## Entry point

For iOS applications, the entry point is expected to be a `@main` struct in any Swift file in the `src` folder.

<div class="grid" markdown>
``` hl_lines="2"
├─ src/
│  ├─ main.swift
│  ╰─ ...
├─ module.yaml
╰─ module.xcodeproj
```

```swift title="src/main.swift"
...
@main
struct iosApp: App {
   ...
}
```
</div>

This is not customizable at the moment.

## Running your application

You can run your application using the `kotlin run` command.

Prerequisite: [Xcode](https://developer.apple.com/xcode/) must be installed, and the first launch must have been run 
already (this is a one-time operation that can also be done via the command `xcodebuild -runFirstLaunch`).

## Xcode Project

Currently, an Xcode project is required to build an iOS application in the Kotlin Toolchain.
It has to be named `module.xcodeproj` and located in the `ios/app` module root directory.

Normally, when the Kotlin project is created via `kotlin init` or via the IDE's Wizard, the appropriate Xcode project is
already there. This is currently the recommended way of creating projects that have an iOS app module.

However, if the Kotlin project is created from scratch, the default buildable Xcode project will be created automatically
after the first project build.
This project can later be customized and checked into a VCS.

If you want to migrate an existing Xcode project so it has Kotlin Toolchain support, you must manually ensure that:

1. it is named `module.xcodeproj` and is located in the root of the `ios/app` module
2. it has a single iOS application target
3. the target has `Debug` & `Release` build configurations, each containing `KOTLIN_CLI_WRAPPER_PATH = <relative path to Kotlin wrapper script>`.
   The path is relative to the Kotlin module root.
4. the target has a script build phase called `Build Kotlin` with the code:
   ```bash
    # !KOTLIN INTEGRATION STEP!
    # This script is managed by the Kotlin Toolchain, do not edit manually!
    "${KOTLIN_CLI_WRAPPER_PATH}" tool xcode-integration
   ```

Changes to the Xcode project that do not break these requirements are allowed.

So the iOS app module layout looks like this:
```
├─ src/
│  ├─ KotlinCode.kt      # optional, if all the code is in the libraries
│  ├─ EntryPoint.swift
│  ├─ Info.plist
├─ module.yaml           # ios/app
╰─ module.xcodeproj      # xcode project
```

!!! tip

    The Xcode project can be built normally from the Xcode IDE, if needed.

## Swift support

!!! info

    Swift sources are only fully supported in the `src` directory of the `ios/app` module.

While swift sources are, strictly speaking, managed by the Xcode project and, as such,
can reside in arbitrary locations, it's not recommended to have them anywhere outside the `src` directory - the tooling
might not work correctly.

To use Kotlin code from Swift, one must import the `KotlinModules` framework.
This framework is built from:

1. the code inside the `ios/app` module itself
2. the modules that `ios/app` module depends on (e.g. `- //shared`)
3. all the external dependencies, transitively

!!! note

    All declarations from the source Kotlin code are accessible to Swift, but external dependencies are not.

## Packaging

On macOS, `kotlin package` (or `kotlin package --format=ipa`) archives the application with `xcodebuild archive` and
exports a signed `.ipa` with `xcodebuild -exportArchive`. The `.ipa` is placed at
`build/packages/ipa/<module-name>-<variant>.ipa`, for example `build/packages/ipa/ios-app-release.ipa`.

A development team must be selected in the Xcode project (**Signing & Capabilities**). The signing style (automatic or
manual) is taken from the Xcode project settings, and `-allowProvisioningUpdates` is always passed so that
automatic signing can create or refresh provisioning profiles.

Use `--ipa-export-options-plist-path` to supply an existing `ExportOptions.plist` file. The file is passed unchanged to
`xcodebuild -exportArchive`, so it can configure the export method, signing, and other Xcode export options. For example:

```bash
kotlin package --format=ipa --ipa-export-options-plist-path=ios-app/ExportOptions.plist
```

Manual signing requires `--ipa-export-options-plist-path`; provide a plist with the provisioning profile mappings for your app.
Without this option, development export options (`method=debugging`, `destination=export`) are generated for automatic
signing from the Xcode project signing settings. Development exports work with a free Apple ID (Personal Team) for installing on local devices;
App Store Connect and ad hoc exports require a paid Apple Developer Program team.

For headless, cloud-managed signing, set the following environment variables:

| Environment variable                  | Description                                                |
|---------------------------------------|------------------------------------------------------------|
| `KOTLIN_TOOLCHAIN_ASC_KEY_PATH`        | Path to the App Store Connect API private key (`.p8` file). |
| `KOTLIN_TOOLCHAIN_ASC_KEY_ID`          | App Store Connect API key ID.                               |
| `KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID`   | App Store Connect API key issuer ID.                        |

These variables must be provided together when IPA packaging is selected; other package formats ignore them.
This is the recommended setup for App Store Connect exports and CI.
Without them, the Apple account signed into Xcode is used.

## Publishing

See the [Publishing an iOS application](../publishing/ios-application.md) guide to publish your application
to App Store Connect and TestFlight.
