/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.problems.reporting.DiagnosticId

/**
 * Diagnostics reported about module semantics (module and template files).
 */
enum class FrontendDiagnosticId : DiagnosticId {
    AliasExpandsToNothing,
    AliasInSinglePlatformModule,
    AliasIntersectsWithNaturalHierarchy,
    AliasIsEmpty,
    AliasUsesNonLeafPlatform,
    AliasUsesUndeclaredPlatform,
    AndroidVersionShouldBeAtLeastMinSdk,
    AndroidVersionTooOld,
    ComposeMaterial3UnknownVersionMapping,
    ComposeHotReloadVersionMismatch,
    ComposeVersionWithoutCompose,
    CredentialsFileDoesNotExist,
    CredentialsFileDoesNotHaveKey,
    DependencyResolutionProblem,
    DependencyVersionIsOverridden,
    IncorrectSettingsSection,
    InvalidKotlinCompilerVersion,
    InvalidXmlForPlexusConfiguration,
    JUnitRequiresHigherJdkVersion,
    JavaIncrementalCompilationRequiresJava16,
    JdkDistributionRequiresLicense,
    JdkVersionTooLow,
    JvmReleaseTooLowForDependency,
    KeystoreFileDoesNotExist,
    KeystorePropertiesDoesNotContainKey,
    KotlinCompilerVersionTooLow,
    KotlinIncrementalCompilationMayBehaveIncorrectly,
    KotlinVersionDoesNotSupportJdk,
    MandatoryFieldInPropertiesFileMustBePresent,
    MavenCentralPublishingEnabledButPublishingDisabled,
    ModuleDependencyDoesntHaveNeededPlatforms,
    ModuleDependencyLoopProblem,
    ModuleDependencySelfProblem,
    NoCatalogValue,
    ObsoleteLibProductType,
    ProductNotDefined,
    ProductPlatformsShouldNotBeEmpty,
    ProductTypeDoesNotSupportPlatform,
    ProductTypeHasNoDefaultPlatforms,
    PublishingSettingsMissingInDependencies,
    RequiredPropertiesMissingForMavenCentralPublication,
    SerializationVersionWithoutSerialization,
    SigningEnabledWithoutPropertiesFile,
    SigningIsRequiredForMavenCentralPublication,
    SourcesJarIsRequiredForMavenCentralPublication,
    TemplateApplicationLoop,
    TemplateNameWithoutPostfix,
    UnknownPluginId,
    UnknownProperty,
    UnknownPropertyInUserControlledType,
    UnknownQualifiers,
    UnresolvedModuleDeclaration,
    UnresolvedModuleDependency,
    UnresolvedTemplate,
    UnsupportedLayout,
    @Deprecated(message = "The corresponding diagnostic no longer exists, remove the support.")
    UselessSetting,
    VersionCannotBeEmpty,
}
