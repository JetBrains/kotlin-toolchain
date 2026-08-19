/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.api.CanBeReferenced
import org.jetbrains.amper.frontend.api.DeprecatedSchema
import org.jetbrains.amper.frontend.api.KnownIntValues
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.ProductTypeSpecific
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.api.TraceableString
import java.util.*
import kotlin.io.path.Path

@JvmInline
value class AndroidVersion(val versionNumber: Int): Comparable<AndroidVersion> {
    override fun compareTo(other: AndroidVersion): Int = versionNumber.compareTo(other.versionNumber)

    override fun toString(): String = versionNumber.toString()
}

class AndroidSettings : SchemaNode() {
    @SchemaDoc("Android SDK license IDs whose terms are explicitly accepted by the user. " +
            "When the license check finds one of these licenses unaccepted, the toolchain " +
            "writes its hash file into the SDK's licenses directory instead of failing, so " +
            "a fresh machine or a CI runner can build without running sdkmanager --licenses. " +
            "Only the listed licenses are accepted; the toolchain never accepts licenses " +
            "implicitly. License texts: https://developer.android.com/studio/intro/update#downloads")
    val acceptedLicenses: List<String> by value(default = emptyList())

    @Misnomers("minApiLevel")
    @SchemaDoc("Minimum API level needed to run the application. " +
            "[Read more](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html)")
    @KnownIntValues(37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21)
    val minSdk by value(AndroidVersion(DefaultVersions.androidMinApiLevel))

    @Misnomers("maxApiLevel")
    @SchemaDoc("Maximum API level on which the application can run. " +
            "[Read more](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html)")
    @DeprecatedSchema("android.max.sdk.deprecated", isError = true)
    @Deprecated("maxSdk isn't recommended to be used in the applications. See https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#maxsdk.")
    val maxSdk by nullableValue<AndroidVersion>()

    @Misnomers("targetApiLevel")
    @SchemaDoc("The target API level for the application. " +
            "[Read more](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html)")
    @KnownIntValues(37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21)
    val targetSdk by referenceValue(::compileSdk, AndroidCompileSdkVersion::apiLevel)

    @CanBeReferenced // by targetSdk
    @Misnomers("compileApiLevel")
    @SchemaDoc("The API level to compile the code. The code can use only the Android APIs up to that API level. " +
            "[Read more](https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/CommonExtension#compileSdk())")
    val compileSdk: AndroidCompileSdkVersion by nested()

    @CanBeReferenced // by applicationId
    @Misnomers("packageName")
    @SchemaDoc("A Kotlin or Java package name for the generated `R` and `BuildConfig` classes. " +
            "[Read more](https://developer.android.com/build/configure-app-module#set-namespace)")
    val namespace by value("org.example.namespace")

    @SchemaDoc("The ID for the application on a device and in the Google Play Store. " +
            "[Read more](https://developer.android.com/build/configure-app-module#set-namespace)")
    @ProductTypeSpecific(ProductType.ANDROID_APP)
    val applicationId by referenceValue(::namespace)

    @SchemaDoc("Application signing settings. " +
    "[Read more](https://developer.android.com/studio/publish/app-signing)")
    @ProductTypeSpecific(ProductType.ANDROID_APP)
    val signing: AndroidSigningSettings by nested()

    @SchemaDoc("Version code. " +
            "[Read more](https://developer.android.com/studio/publish/versioning#versioningsettings)")
    @ProductTypeSpecific(ProductType.ANDROID_APP)
    val versionCode by value(1)

    @SchemaDoc("Version name. " +
            "[Read more](https://developer.android.com/studio/publish/versioning#versioningsettings)")
    @ProductTypeSpecific(ProductType.ANDROID_APP)
    val versionName by value("unspecified")

    @Misnomers("packagingOptions")
    @SchemaDoc("Packaging options for java resource files.")
    @ProductTypeSpecific(ProductType.ANDROID_APP)
    val resourcePackaging: AndroidJavaResourcesPackagingSettings by nested()

    @SchemaDoc("Configure [Kotlin Parcelize](https://developer.android.com/kotlin/parcelize) to automatically " +
            "implement the `Parcelable` interface for classes annotated with `@Parcelize`.")
    val parcelize: ParcelizeSettings by nested()

    // The default should be at least as high as the minimal version supported by the AGP we use:
    // https://developer.android.com/build/releases/gradle-plugin (see SDK Build Tools Minimum version field)
    @SchemaDoc("Version of [SDK Build Tools](https://developer.android.com/tools/releases/build-tools) to use.")
    val buildToolsVersion by value(DefaultVersions.androidBuildTools)
}

class AndroidCompileSdkVersion : SchemaNode() {
    @Shorthand
    @SchemaDoc("The Android API level to compile the project against.")
    @KnownIntValues(37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21)
    val apiLevel by value(AndroidVersion(DefaultVersions.androidCompileApiLevel))

    @SchemaDoc("Minor API level of the Android API.")
    val minorApiLevel by value(0)

    @SchemaDoc("The Android SDK extension level to compile the project against. " +
            "[Read more](https://developer.android.com/guide/sdk-extensions)")
    val sdkExtension by nullableValue<Int>()
}

class AndroidSigningSettings : SchemaNode() {
    @Shorthand
    @SchemaDoc("Enables signing with keystore")
    val enabled by value(default = false)

    @Misnomers("keystoreFile")
    @SchemaDoc("Properties file where the keystore data is stored.")
    val propertiesFile by value(Path("keystore.properties"))
}

enum class KeystoreProperty(val key: String) {
    StoreFile("storeFile"),
    StorePassword("storePassword"),
    KeyAlias("keyAlias"),
    KeyPassword("keyPassword")
}

val Properties.storeFile: String? get() = getProperty(KeystoreProperty.StoreFile.key)
val Properties.storePassword: String? get() = getProperty(KeystoreProperty.StorePassword.key)
val Properties.keyAlias: String? get() = getProperty(KeystoreProperty.KeyAlias.key)
val Properties.keyPassword: String? get() = getProperty(KeystoreProperty.KeyPassword.key)

class ParcelizeSettings : SchemaNode() {

    @Shorthand
    @SchemaDoc("Enables [Parcelize](https://developer.android.com/kotlin/parcelize). When enabled, an " +
            "implementation of the `Parcelable` interface is automatically generated for classes annotated with " +
            "`@Parcelize`.")
    val enabled by value(default = false)

    @SchemaDoc("The full-qualified name of additional annotations that should be considered as `@Parcelize`. " +
            "This is useful if you need to annotate classes in common code shared between different platforms, where " +
            "the real `@Parcelize` annotation is not available. In that case, create your own common annotation and " +
            "add its fully-qualified name here so that Parcelize recognizes it.")
    val additionalAnnotations: List<TraceableString> by value(default = emptyList())
}

class AndroidJavaResourcesPackagingSettings : SchemaNode() {
    @SchemaDoc("The set of excluded patterns. " +
            "Java resources matching any of these patterns do not get packaged in the APK.<br>" +
            "Example: '**/*.md', 'META-INF/LICENSE.txt', etc.")
    val excludes by value<List<TraceableString>>(default = emptyList())

    @SchemaDoc("The set of patterns for which matching java resources are merged. " +
            "For each java resource APK entry path matching one of these patterns, " +
            "all java resources with that path are concatenated and packaged as a single entry in the APK.<br>" +
            "Example: '**/*.properties', 'META-INF/NOTICE.md', etc.")
    val merges by value<List<TraceableString>>(default = emptyList())

    @SchemaDoc("The set of patterns for which the first occurrence is packaged in the APK. " +
            "For each java resource APK entry path matching one of these patterns, " +
            "only the first java resource found with that path gets packaged in the APK.<br>" +
            "Example: '**/*.version', 'META-INF/*.kotlin_module', etc.")
    val pickFirsts by value<List<TraceableString>>(default = emptyList())
}
