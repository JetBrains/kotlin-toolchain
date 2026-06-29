/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.publiation

import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.mavenCoordinatesTrimmed
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment

fun AmperModule.rootPublicationCoordinates(): MavenCoordinates {
    val commonFragment = fragments.find { !it.isTest && it.fragmentDependencies.isEmpty() }
        ?: error("Cannot generate root Maven coordinates for module '$userReadableName': no root fragment")

    return commonFragment.mavenCoordinates(artifactIdSuffix = "")
}

// todo (AB): [AMPER-5245] Support publishing with classifier.
fun Fragment.mavenCoordinates(artifactIdSuffix: String): MavenCoordinates = mavenCoordinatesTrimmed(
    groupId = settings.publishing.group
        ?: error("Missing 'group' in publishing settings of fragment '${name}' of module '${module.userReadableName}'"),
    artifactId = (settings.publishing.artifactId ?: module.userReadableName.lowercase()) + artifactIdSuffix,
    version = settings.publishing.version
        ?: error("Missing 'version' in publishing settings of fragment '${name}' of module '${module.userReadableName}'")
)