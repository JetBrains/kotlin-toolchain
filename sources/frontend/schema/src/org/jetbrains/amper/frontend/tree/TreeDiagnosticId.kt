/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.problems.reporting.DiagnosticId

/**
 * Diagnostics reported by the tree parser.
 */
enum class TreeDiagnosticId : DiagnosticId {
    AliasesAreNotSupported,
    CompoundKeysAreNotSupported,
    ConflictingProperties,
    ExpectedKeyValue,
    ExpectedSingleKeyValuePair,
    DeprecatedProperty,
    InvalidPath,
    InvalidPathBackslash,
    InvalidProjectRootRelativePath,
    MappingKeyIsMissing,
    MappingShouldHaveSingleKeyValue,
    MissingValue,
    MultipleQualifiersAreNotSupported,
    MultipleYAMLDocumentsAreNotSupported,
    NestedReferencesAreNotSupported,
    NonReferenceableElement,
    NoValueForRequiredProperty,
    PropertyIsNotSettable,
    ReferenceCannotBeUsedInStringInterpolation,
    ReferenceIsEmpty,
    ReferenceHasUnexpectedType,
    ReferenceMissesClosingBrace,
    ReferenceResolutionRootNotFound,
    ReferenceResolutionLoop,
    ReferenceSegmentIsEmpty,
    ReferenceSegmentIsNotMapping,
    ReferencesAreNotSupported,
    ReferencesAreNotSupportedInKeys,
    TagsAreNotSupported,
    TypeMismatch,
    TypeDoesNotSupportInterpolation,
    UnexpectedNull,
    UnexpectedValue,
    UnknownEnumValue,
    UnresolvedReference,
    ReferenceMemberAccessOnNullable,

    // Domain-specific
    CoordinatesInGradleFormat,
    InvalidTaskActionType,
    BomIsNotSupported,
    LocalDependenciesAreNotSupported,
    MavenClassifiersAreNotSupported,
    MavenCoordinatesHaveLineBreak,
    MavenCoordinatesHavePartEndingWithDot,
    MavenCoordinatesHaveSlash,
    MavenCoordinatesHaveSpace,
    MavenCoordinatesHaveTooFewParts,
    MavenCoordinatesHaveTooManyParts,
    MavenCoordinatesShouldBuildValidPath,
    MissingTaskActionType,
    WrongDependencyFormat,
    SwiftPMDependencyIsNotSupported,
    SwiftPMDependencyInNonApplePlatform,
    TestOnlySwiftPMDependency,
}