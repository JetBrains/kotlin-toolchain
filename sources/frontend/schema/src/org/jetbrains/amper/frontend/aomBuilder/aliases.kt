/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.leaves
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.schema.ModuleProduct
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls

/**
 * Maps declared aliases to the set of leaf platforms they represent in the resulting product.
 *
 * - The platform sets are expanded but reported as an error. We don't support it at the moment, but it was the user's
 *   intention most likely.
 * - If during the expansion platform set doesn't intersect with any of the module platforms, we report an error
 *   on the platform set.
 * - If any of the leaf platforms mentioned in the alias isn't part of the module platforms, we report
 *   an error on this leaf platform.
 * - We take all the leaf platforms from the previous step and intersect them with the module platforms. If the
 *   resulting set is empty, we report an error on the alias and skip it.
 * - If the resulting set matches some platform set or platform from the natural hierarchy, we report a warning on
 *   the alias.
 * - The alias and the filtered set of platform is added as a map entry.
 */
context(problemReporter: ProblemReporter)
internal fun mapAliasesToLeaves(
    declaredAliases: Map<TraceableString, List<TraceableValue<Platform>>>,
    product: ModuleProduct,
): Map<TraceableString, Set<Platform>> = buildMap {
    val productLeafPlatforms = product.platforms.leaves
    for ([alias, aliasPlatforms] in declaredAliases) {
        val [declaredLeafPlatforms, nonLeafPlatforms] = aliasPlatforms.partition { it.value.isLeaf }
        for (nonLeafPlatform in nonLeafPlatforms) {
            val canBeExpandedTo = (nonLeafPlatform.value.leaves intersect productLeafPlatforms).takeIf { it.isNotEmpty() }
            problemReporter.reportMessage(AliasUsesNonLeafPlatform(alias, nonLeafPlatform, canBeExpandedTo))

            if (canBeExpandedTo == null) {
                problemReporter.reportMessage(AliasWithNonLeafPlatformExpandsToNothing(alias, nonLeafPlatform))
            }
        }

        for (leafPlatform in declaredLeafPlatforms) {
            if (leafPlatform.value !in productLeafPlatforms) {
                problemReporter.reportMessage(AliasUsesUndeclaredPlatform(alias, leafPlatform))
            }
        }

        val applicableLeafPlatforms = aliasPlatforms.flatMap { it.value.leaves } intersect productLeafPlatforms

        if (applicableLeafPlatforms.isEmpty()) {
            problemReporter.reportMessage(AliasIsEmpty(alias))
            continue
        }

        val existingNaturalHierarchyPlatformSet = Platform.naturalHierarchyExt.entries.find {
            it.value == applicableLeafPlatforms
        }
        if (existingNaturalHierarchyPlatformSet != null) {
            problemReporter.reportMessage(AliasIntersectsWithNaturalHierarchy(alias, existingNaturalHierarchyPlatformSet.key))
        }

        put(alias, applicableLeafPlatforms)
    }
}

data class AliasesAreNotSupportedInSinglePlatformModule(override val element: PsiElement)
    : PsiBuildProblem(Level.Warning, BuildProblemType.Generic) {

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasInSinglePlatformModule
    override val message: @Nls String
        get() = SchemaBundle.message("aliases.are.not.supported.in.single.platform.module")
}

data class AliasUsesNonLeafPlatform(
    val alias: TraceableString,
    val nonLeafPlatform: TraceableValue<Platform>,
    @UsedInIdePlugin
    val canBeExpandedTo: Set<Platform>?,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {

    override val element: PsiElement
        get() = nonLeafPlatform.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasUsesNonLeafPlatform
    override val message: @Nls String
        get() = SchemaBundle.message("alias.uses.non.leaf.platform", alias.value, nonLeafPlatform.value.pretty)
}

data class AliasWithNonLeafPlatformExpandsToNothing(
    val alias: TraceableString,
    val nonLeafPlatform: TraceableValue<Platform>,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {

    override val element: PsiElement
        get() = nonLeafPlatform.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasExpandsToNothing
    override val message: @Nls String
        get() = SchemaBundle.message(
            "alias.with.non.leaf.platform.expands.to.nothing",
            alias.value,
            nonLeafPlatform.value.pretty,
            nonLeafPlatform.value.leaves.map { "`${it.pretty}`" },
        )
}

data class AliasUsesUndeclaredPlatform(
    val alias: TraceableString,
    val undeclaredPlatform: TraceableValue<Platform>,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {

    override val element: PsiElement
        get() = undeclaredPlatform.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasUsesUndeclaredPlatform
    override val message: @Nls String
        get() = SchemaBundle.message("alias.uses.undeclared.platform", alias.value, undeclaredPlatform.value.pretty)
}

data class AliasIsEmpty(val alias: TraceableString) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {

    override val element: PsiElement
        get() = alias.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasIsEmpty
    override val message: @Nls String
        get() = SchemaBundle.message("alias.is.empty", alias.value)
}

data class AliasIntersectsWithNaturalHierarchy(
    val alias: TraceableString,
    val existingPlatform: Platform,
) : PsiBuildProblem(Level.Warning, BuildProblemType.Generic) {

    override val element: PsiElement
        get() = alias.extractPsiElement()
    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.AliasIntersectsWithNaturalHierarchy
    override val message: @Nls String
        get() = SchemaBundle.message("alias.intersects.with.natural.hierarchy", alias.value, existingPlatform.pretty)
}