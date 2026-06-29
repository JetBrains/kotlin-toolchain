/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

val AomModelDiagnosticFactories: List<AomModelDiagnosticFactory> = listOf(
    JvmReleaseLowerThanDependencies,
    ModuleDependencyLoopFactory,
    PublishingSettingsMissingInDependencies,
    ComposeHotReloadVersionConflictFactory,
)

val AomSingleModuleDiagnosticFactories: List<AomSingleModuleDiagnosticFactory> = listOf(
    AndroidVersionShouldBeAtLeastMinSdkFactory,
    ComposeVersionWithDisabledCompose,
    JavaIncrementalCompilationRequiresJava16Factory,
    JdkDistributionRequiresLicenseFactory,
    JUnitRequiresHigherJdkVersionFactory,
    KotlinIncrementalCompilationMayBehaveIncorrectlyFactory,
    KotlinVersionDoesNotSupportJdkFactory,
    SerializationVersionWithDisabledSerialization,
    SigningEnabledWithoutPropertiesFileFactory,
    KeystorePropertiesDoesNotContainKeyFactory,
    MandatoryFieldInPropertiesFileMustBePresentFactory,
    MavenCentralRequirementsCheckingFactory,
    KeystoreMustExistFactory,
    ModuleDependencyDoesntHaveNeededPlatformsFactory,
)
