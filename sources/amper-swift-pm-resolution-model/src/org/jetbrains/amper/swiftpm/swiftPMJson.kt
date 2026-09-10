/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.json.Json

val swiftPMJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    allowStructuredMapKeys = true
}

/**
 * The JSON format to use when *writing* [SwiftPMImportMetadata] into a published artifact.
 *
 * It mirrors the format KGP publishes with, which notably keeps `explicitNulls` enabled. That matters: in KGP's model
 * the deployment version fields are nullable but have no default, so kotlinx treats them as required keys. Omitting
 * them (as [swiftPMJson] would) makes KGP consumers fail with a `MissingFieldException` instead of reading a null.
 *
 * [swiftPMJson] stays fine for *reading* published metadata, since a missing key is decoded as null there.
 */
val swiftPMPublicationJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
