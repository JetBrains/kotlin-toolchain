/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.json.Json

val swiftPMJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    // Required for the class keys of TransitiveSwiftPMMetadata.metadataByDependencyIdentifier, which we only ever
    // serialize to our own internal files (the published format has no such maps).
    allowStructuredMapKeys = true
}