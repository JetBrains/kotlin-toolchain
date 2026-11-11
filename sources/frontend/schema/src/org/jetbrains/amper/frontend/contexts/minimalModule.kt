/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.contexts

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.api.isExplicitlySet
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.keyValueAsBuildProblemSource
import org.jetbrains.amper.frontend.leaves
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.schema.ModuleProduct
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.tree.TreeRefiner
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.frontend.tree.instance
import org.jetbrains.amper.frontend.tree.reading.UnknownPropertiesParsingMode
import org.jetbrains.amper.frontend.tree.reading.readTree
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.replayProblemsTo
import org.jetbrains.yaml.psi.YAMLPsiElement

val MinimalModule.unwrapAliases get() = aliases?.mapValues { it.value.leaves }.orEmpty()

val defaultContextsInheritance by lazy {
    PlatformsInheritance() + MainTestInheritance + DefaultInheritance
}

context(problemReporter: ProblemReporter, _: FrontendPathResolver, _: AmperFrontendProjectRoot)
internal fun tryReadMinimalModule(moduleFilePath: VirtualFile): MinimalModuleHolder? {
    val collectingReporter = CollectingProblemReporter()
    val minimalModule = context(collectingReporter) {
        val moduleTree = readTree(
            moduleFilePath,
            declaration = DeclarationOfMinimalModule,
            unknownPropertiesMode = UnknownPropertiesParsingMode.SkipSilently,
        )

        TreeRefiner().refineTree(moduleTree, EmptyContexts).completeTree()?.instance<MinimalModule>()
    }
    if (minimalModule == null) {
        // Replay errors to the original reporter if we couldn't even create the minimal module.
        // Otherwise, messages will be reported when reading the full module, so we should swallow them here.
        collectingReporter.replayProblemsTo(problemReporter)
        return null
    }

    val specifiedUnsupportedPlatforms = minimalModule.product.specifiedUnsupportedPlatforms
    specifiedUnsupportedPlatforms.forEach { unsupportedPlatform ->
        problemReporter.reportUnsupportedPlatform(unsupportedPlatform, minimalModule.product.type)
    }
    if (specifiedUnsupportedPlatforms.isNotEmpty()) {
        return null
    }

    @Suppress("DEPRECATION")
    if (minimalModule.product.type in setOf(ProductType.KMP_LIB, ProductType.LIB)
        && !minimalModule.product.platformsDelegate.isExplicitlySet
    ) {
        problemReporter.reportMissingExplicitPlatforms(minimalModule.product)
        return null
    }

    if (minimalModule.product.platforms.isEmpty()) {
        problemReporter.reportBundleError(
            source = minimalModule.product.platformsDelegate.asBuildProblemSource(),
            diagnosticId = FrontendDiagnosticId.ProductPlatformsShouldNotBeEmpty,
            messageKey = "product.platforms.should.not.be.empty",
        )
        return null
    }

    return MinimalModuleHolder(
        // We can cast here because we know that minimal module
        // properties should be used outside any context.
        module = minimalModule,
    )
}

private val ModuleProduct.specifiedUnsupportedPlatforms: List<TraceableValue<Platform>>
    get() = platforms.filter { it.value !in type.supportedPlatforms }

private fun ProblemReporter.reportUnsupportedPlatform(
    unsupportedPlatform: TraceableValue<Platform>,
    productType: ProductType,
) {
    reportBundleError(
        source = unsupportedPlatform.trace.asBuildProblemSource(),
        diagnosticId = FrontendDiagnosticId.ProductTypeDoesNotSupportPlatform,
        messageKey = "product.unsupported.platform",
        productType.schemaValue,
        unsupportedPlatform.value.pretty,
        productType.supportedPlatforms.map { "`${it.pretty}`" },
        problemType = BuildProblemType.InconsistentConfiguration,
    )
}

private fun ProblemReporter.reportMissingExplicitPlatforms(product: ModuleProduct) {
    val isYaml = product.typeDelegate.extractPsiElementOrNull()?.parent is YAMLPsiElement
    reportBundleError(
        source = product.typeDelegate.keyValueAsBuildProblemSource(),
        diagnosticId = FrontendDiagnosticId.ProductTypeHasNoDefaultPlatforms,
        messageKey = if (isYaml) {
            "product.type.does.not.have.default.platforms"
        } else {
            "product.type.does.not.have.default.platforms.amperlang"
        },
        product.type.schemaValue,
    )
}

internal class MinimalModuleHolder(val module: MinimalModule) {
    val platformsInheritance by lazy {
        val aliases = module.aliases.orEmpty().mapValues { it.value.leaves }
        PlatformsInheritance(aliases)
    }
}