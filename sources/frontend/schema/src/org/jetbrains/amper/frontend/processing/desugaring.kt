/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.processing

import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.DesugaringSettings
import org.jetbrains.amper.frontend.schema.UnscopedCatalogDependency
import org.jetbrains.amper.frontend.schema.UnscopedExternalMavenDependency
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.buildTree
import org.jetbrains.amper.frontend.tree.invoke
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.tree.withTrace
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.frontend.types.generated.*

/**
 * Defaults the desugaring library: an omitted `library` becomes the default `desugar_jdk_libs` artifact,
 * and a library declared without a version gets the default version.
 *
 * A library or version declared by the user always takes precedence over the defaults.
 * The defaults are also set when desugaring is disabled: it can be enabled per-fragment
 * (e.g. in `settings@android`), which cannot be seen from the common settings available here.
 */
context(types: SchemaTypingContext)
internal fun MappingNode.configureDesugaringDefaults(desugaringSettings: DesugaringSettings): MappingNode {
    return when (val library = desugaringSettings.library) {
        null -> mergeTrees(this, defaultDesugaringLibraryTree())
        is UnscopedExternalMavenDependency ->
            if (library.version == null) mergeTrees(this, defaultDesugaringVersionTree()) else this
        // The settings are read before the catalog substitution, so a catalog reference is not resolved in them
        // yet. In the tree, it is already substituted at this point, so the merged default version only
        // applies if the catalog reference didn't provide one.
        is UnscopedCatalogDependency -> mergeTrees(this, defaultDesugaringVersionTree())
    }
}

/**
 * The patched version is traced as derived, not a plain default, because it completes a user-declared library.
 */
private val defaultDesugaringVersionTrace = TransformedValueTrace(
    description = "default version",
    sourceValue = TraceableString(DefaultVersions.androidDesugarJdkLibs, DefaultTrace),
)

context(types: SchemaTypingContext)
private fun defaultDesugaringLibraryTree() = buildTree(types.moduleDeclaration) {
    settings {
        android {
            desugaring {
                library(DeclarationOfUnscopedExternalMavenDependency) {
                    groupId("com.android.tools")
                    artifactId("desugar_jdk_libs")
                    withTrace(defaultDesugaringVersionTrace) {
                        version(DefaultVersions.androidDesugarJdkLibs)
                    }
                }
            }
        }
    }
}

context(types: SchemaTypingContext)
private fun defaultDesugaringVersionTree() = buildTree(types.moduleDeclaration) {
    settings {
        android {
            desugaring {
                library(DeclarationOfUnscopedExternalMavenDependency) {
                    withTrace(defaultDesugaringVersionTrace) {
                        version(DefaultVersions.androidDesugarJdkLibs)
                    }
                }
            }
        }
    }
}
