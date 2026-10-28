---
description: Learn how to publish an iOS application to App Store Connect and TestFlight.
---
# Publishing an iOS application

You need a Mac with Xcode and an [Apple Developer Program](https://developer.apple.com/programs/) membership.

## Publish with `kotlin publish testflight`

!!! info "Preview"

    `kotlin publish testflight` is in preview, and is likely to change. Don't hesitate to
    [share your feedback](https://youtrack.jetbrains.com/newIssue?project=KTC)!

The special `testflight` repository ID publishes an `ios/app` module to App Store Connect and TestFlight. It is built
into `kotlin publish` and does not need to be declared in the project's repositories. For example:

```bash
kotlin publish testflight
```

If the project has more than one iOS app module, `-m <ios-module-name>` is required to specify which module to publish.
The command checks for Xcode, ensures automatic signing is selected,
registers the bundle ID if needed, and uploads a release build. If the App Store Connect app record does not exist, the
command guides you through creating it. Apple then processes the uploaded build for TestFlight.

The command requires an App Store Connect team API key with API access and an Admin role for automatic provisioning.
The key is configured with the following environment variables:

| Environment variable                 | Description                                                 |
|--------------------------------------|-------------------------------------------------------------|
| `KOTLIN_TOOLCHAIN_ASC_KEY_PATH`      | Path to the App Store Connect API private key (`.p8` file). |
| `KOTLIN_TOOLCHAIN_ASC_KEY_ID`        | App Store Connect API key ID.                               |
| `KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID` | App Store Connect API key issuer ID.                        |

In an interactive terminal session, all of these values can be specified interactively instead. The command then
offers a wizard-like setup that guides you through the necessary steps when you publish for the first time: it prompts
for the `.p8` key path, Key ID, and Issuer ID, saves them in `app-store-connect-auth.properties` in the module
directory, and adds that file to `.gitignore`. It also asks you to confirm or fix other parts of the setup, such as the
bundle ID, the app record, or the encryption declaration.

When running non-interactively (for example, on CI), nothing is asked. The credentials are taken from the
`app-store-connect-auth.properties` file in the module directory if it exists (for example, saved by an earlier
interactive run), and from the environment variables otherwise. Values from the file take precedence over the
environment variables. The command exits with an error if something is missing or wrong at any stage of the setup.

!!! warning "Automatic signing only"

    `kotlin publish testflight` supports automatic signing for straightforward setups and is intended to help you get
    started. It does not cover manual signing or more complex publishing workflows. For those scenarios, use another
    tool such as [fastlane](https://docs.fastlane.tools/getting-started/ios/setup/), which can integrate directly with
    the Xcode project.

## Publish manually

Given that there is a regular [Xcode project](../product-types/ios-app.md#xcode-project) for an `ios/app` module,
you can use it to configure signing, create a release archive, and upload the application to App Store Connect manually.

### Prepare the application

Open the `module.xcodeproj` file of the iOS application module in Xcode, for example:

```bash
open ios-app/module.xcodeproj
```

In the project navigator, select the project and then the application target. Configure the following settings before
creating an archive:

1. Under **Signing & Capabilities**, select your development team and replace the default bundle identifier with a
   unique identifier for your application.
2. Under **General | Minimum Deployments**, select the oldest iOS version that your application supports.
3. Under **Info | Custom iOS Target Properties | Supported interface orientations**, keep only the orientations your
   application supports.
4. Add an `AppIcon` asset catalog under the module's `src` directory and provide the application icon. A 1024×1024
   image is sufficient when the asset uses Xcode's single-size app icon format.
5. Under **General | Identity**, set the public-facing **Version** and the unique **Build** number.

!!! warning "Use a new build number for every upload"

    App Store Connect rejects another upload with the same version and build number. Increase the **Build** value
    (`CFBundleVersion`) before every upload. Increase **Version** (`CFBundleShortVersionString`) when publishing a new
    application version.

Register the same bundle identifier in
[Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/bundleId/add/bundle),
then [create the application in App Store Connect](https://appstoreconnect.apple.com/apps). The bundle identifier in
Xcode, the registered identifier, and the App Store Connect application must match.

### Distribute from the command line

You can ask your coding agent to archive and upload the iOS application to TestFlight, or run the commands below
manually. Run them from the Kotlin project root and replace the module path and scheme if they differ in your project.
You can list the available schemes with:

```bash
xcodebuild -list -project ios-app/module.xcodeproj
```

Create a release archive:

```bash
xcodebuild \
  -project ios-app/module.xcodeproj \
  -scheme app \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/release/ios-app.xcarchive \
  -allowProvisioningUpdates \
  archive
```

Create `ios-app/ExportOptions.plist` to configure an App Store Connect upload:

```xml title="ios-app/ExportOptions.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>destination</key>
    <string>upload</string>
    <key>signingStyle</key>
    <string>automatic</string>
</dict>
</plist>
```

Export and upload the archive:

```bash
xcodebuild -exportArchive \
  -archivePath build/release/ios-app.xcarchive \
  -exportOptionsPlist ios-app/ExportOptions.plist \
  -exportPath build/release/export \
  -allowProvisioningUpdates
```

With `destination` set to `upload`, the export command uploads the application to App Store Connect. After Apple
finishes processing the build, it becomes available in App Store Connect and can be assigned to TestFlight testers.

### Distribute from Xcode

You can perform the same archive and upload steps in Xcode:

1. In the macOS menu bar, select **Product | Archive**.
2. After the archive is created successfully, Xcode opens the Organizer window. Select the new archive and click
   **Distribute App**.
3. Select **App Store Connect**, then follow the upload flow.

After Apple finishes processing the build, continue in App Store Connect to distribute it through TestFlight or submit
it for App Store review.
