/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/*
 * The `Distributions` array in the Xcode archive's `Info.plist` is Xcode's own, undocumented metadata.
 * We rely on `xcodebuild -exportArchive` with the `upload` destination recording exactly one successful distribution
 * there when it succeeds. If this doesn't hold, our assumptions about Xcode are wrong, so these are internal errors
 * rather than user-readable ones.
 */

/**
 * Reads the `Distributions` array from the Xcode archive's `Info.plist` at the given [path].
 * A fresh archive has no `Distributions` key until it is uploaded for the first time, so an empty list is returned.
 */
internal fun readXcarchiveDistributions(path: Path): List<*> {
    if (!path.isRegularFile()) {
        error("Xcode archive metadata is missing: '$path'.")
    }
    val distributions = readPlist(path)["Distributions"] ?: return emptyList<Any>()
    return distributions as? List<*>
        ?: error("The Distributions field in Xcode archive metadata at '$path' is not an array.")
}

internal fun distributionIdentifiers(distributions: List<*>): Set<String> = distributions.map { distribution ->
    (distribution as? Map<*, *>)?.stringValue("identifier")?.takeIf(String::isNotBlank)
        ?: error("An Xcode archive distribution is missing its identifier.")
}.toSet()

internal fun newUploadId(identifiersBefore: Set<String>, distributionsAfter: List<*>): String {
    val newDistributions = distributionsAfter.filter { distribution ->
        val identifier = (distribution as? Map<*, *>)?.stringValue("identifier")
        identifier == null || identifier !in identifiersBefore
    }
    check(newDistributions.size == 1) {
        "Expected exactly one new Xcode archive distribution after a successful upload, but found ${newDistributions.size}."
    }

    val distribution = checkNotNull(newDistributions.single() as? Map<*, *>) {
        "The new Xcode archive distribution is not a dictionary."
    }
    val identifier = checkNotNull(distribution.stringValue("identifier")?.takeIf(String::isNotBlank)) {
        "The new Xcode archive distribution is missing its identifier."
    }
    val destination = distribution.stringValue("destination")
    check(destination == "upload") {
        "The new Xcode archive distribution has destination '$destination', not 'upload'."
    }

    val uploadEvent = distribution["uploadEvent"] as? Map<*, *>
    val state = uploadEvent?.stringValue("state")
    check(state == "success") {
        val errors = (uploadEvent?.get("errors") as? List<*>)?.takeIf { it.isNotEmpty() }
        val details = errors?.let { " Details: $it" }.orEmpty()
        "The new Xcode archive distribution has the state '$state' after a successful upload.$details"
    }
    return identifier
}

private fun Map<*, *>.stringValue(key: String): String? = get(key) as? String
