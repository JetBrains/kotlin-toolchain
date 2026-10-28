---
description: Learn how to publish an Android application to Google Play.
---
# Publishing an Android application

Publish an `android/app` module to Google Play as a signed Android App Bundle (AAB). You need a Google Play Console
developer account.

## Configure the application

Set a unique application ID and an initial version in `android-app/module.yaml`:

```yaml title="android-app/module.yaml"
product: android/app

settings:
  android:
    applicationId: com.example.myapp
    versionCode: 1
    versionName: "1.0"
```

The `applicationId` uniquely identifies the application in Google Play and cannot be changed after you upload the
first artifact.

Before uploading the application:

1. [Prepare signing](../product-types/android-app.md#signing): create `android-app/keystore.properties` and generate
   the upload key in `android-app/release.keystore`.
2. [Package the application](../product-types/android-app.md#packaging) with `kotlin package -m android-app`.
   This produces a signed release AAB and prints its path. Use that AAB for the upload.

## Upload the bundle

If you haven't created the application yet, open [Google Play Console](https://play.google.com/console/) and select
**Create app** to set it up before uploading your first bundle.

Open the application in Google Play Console, go to **Test and release**, and select the appropriate testing or
production track. Create a release and upload the generated `.aab` file.

Google Play validates and processes the bundle before making the release available to testers or users.

!!! note "For future uploads"

    Increase `versionCode` in `android-app/module.yaml` before every new upload: Google Play rejects bundles with a
    previously used version code. Update the user-facing `versionName` when publishing a new application version.

## Build from IntelliJ IDEA or Android Studio

Generating a signed bundle from **Build | Generate Signed App Bundle or APK** is not supported for Kotlin Toolchain
projects yet. Use the Kotlin CLI to create the AAB.
