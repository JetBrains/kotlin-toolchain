/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.aomBuilder.plugins.buildAndApplyPlugins
import org.jetbrains.amper.frontend.aomBuilder.swiftpm.diagnoseSwiftPMDependencyInNonApplePlatform
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.catalogs.builtInCatalog
import org.jetbrains.amper.frontend.catalogs.substituteCatalogDependencies
import org.jetbrains.amper.frontend.contexts.DefaultInheritance
import org.jetbrains.amper.frontend.contexts.MainTestInheritance
import org.jetbrains.amper.frontend.contexts.PathCtx
import org.jetbrains.amper.frontend.contexts.PathInheritance
import org.jetbrains.amper.frontend.contexts.plus
import org.jetbrains.amper.frontend.contexts.tryReadMinimalModule
import org.jetbrains.amper.frontend.diagnostics.AomModelDiagnosticFactories
import org.jetbrains.amper.frontend.diagnostics.AomSingleModuleDiagnosticFactories
import org.jetbrains.amper.frontend.diagnostics.TreeDiagnosticFactories
import org.jetbrains.amper.frontend.diagnostics.UnresolvedModuleDependency
import org.jetbrains.amper.frontend.plus
import org.jetbrains.amper.frontend.processing.addImplicitDependencies
import org.jetbrains.amper.frontend.processing.configureLombokDefaults
import org.jetbrains.amper.frontend.processing.configurePluginDefaults
import org.jetbrains.amper.frontend.processing.configureSpringBootDefaults
import org.jetbrains.amper.frontend.processing.substituteComposeOsSpecific
import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.schema.CatalogDependency
import org.jetbrains.amper.frontend.schema.Dependency
import org.jetbrains.amper.frontend.schema.ExternalMavenDependency
import org.jetbrains.amper.frontend.schema.InternalDependency
import org.jetbrains.amper.frontend.schema.Module
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.schema.Settings
import org.jetbrains.amper.frontend.schema.UnscopedExternalMavenDependency
import org.jetbrains.amper.frontend.schema.swiftpm.convertSchema
import org.jetbrains.amper.frontend.schema.toMavenCoordinates
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.TreeRefiner
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.frontend.tree.instance
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.frontend.types.maven.MavenPluginDescriptionAdapter
import org.jetbrains.amper.maven.MavenPluginXml
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.system.info.SystemInfo
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * Parses the configuration files of this [AmperProjectContext] and builds the project model.
 *
 * All errors and warnings are reported to the given [problemReporter].
 *
 * The returned model is built on a best-effort basis. The contracts for all returned data is only respected if no
 * errors are reported.
 *
 * @param pluginData plugin data that should be used for reading the project model. The default is pre-built plugin
 *  data but the client is free to provide their own. E.g., IDE can build the freshest plugin data directly from the
 *  Kotlin sources in-memory and provide it to the project model reader.
 */
@UsedInIdePlugin
context(problemReporter: ProblemReporter)
fun AmperProjectContext.readProjectModel(
    pluginData: List<PluginData>,
    mavenPluginXmls: List<MavenPluginXml>,
): Model = doReadProjectModel(pluginData, mavenPluginXmls)

/**
 * Testable version of [readProjectModel].
 */
context(problemReporter: ProblemReporter)
internal fun AmperProjectContext.doReadProjectModel(
    pluginData: List<PluginData>,
    mavenPluginXmls: List<MavenPluginXml>,
    systemInfo: SystemInfo = SystemInfo.CurrentHost,
): Model = context(
    frontendPathResolver,
    projectRoot,
    SchemaTypingContext(pluginData, mavenPluginXmls),
    systemInfo,
) {
    // Parse all module files and perform preprocessing (templates, catalogs, etc.)
    val templateCache = hashMapOf<Path, MappingNode>()
    val rawModulesByFile = amperModuleFiles.associateWith {
        readModuleMergedTree(it, projectVersionsCatalog, templateCache)
    }

    val unreadableModuleFiles = rawModulesByFile.filterValues { it == null }.keys
    val rawModules = rawModulesByFile.values.filterNotNull()

    // Build [AmperModule]s.
    val modules = buildAmperModules(rawModules)

    // Do some alterations to the built modules.
    modules.forEach { it.module.addImplicitDependencies() }

    // Load plugins that exist in the project
    val amperPlugins = buildAndApplyPlugins(pluginData, modules)
    
    // Add read maven plugin xmls.
    modules.forEach { 
        it.module.amperMavenPluginsDescriptions = mavenPluginXmls.map(::MavenPluginDescriptionAdapter) 
    }

    // Perform diagnostics.
    AomSingleModuleDiagnosticFactories.forEach { diagnostic ->
        modules.forEach { diagnostic.analyze(it.module, problemReporter) }
    }
    val model = DefaultModel(
        projectRoot = projectRoot.path,
        modules = modules.map { it.module },
        unreadableModuleFiles = unreadableModuleFiles,
        amperPlugins = amperPlugins,
    )
    AomModelDiagnosticFactories.forEach { it.analyze(model, problemReporter) }
    return model
}

context(
    problemReporter: ProblemReporter,
    types: SchemaTypingContext,
    pathResolver: FrontendPathResolver,
    _: AmperFrontendProjectRoot,
    _: SystemInfo,
)
internal fun readModuleMergedTree(
    moduleFile: VirtualFile,
    projectVersionsCatalog: VersionCatalog?,
    templatesCache: MutableMap<Path, MappingNode> = hashMapOf(),
): ModuleBuildCtx? {
    val moduleCtx = PathCtx(moduleFile.toNioPath(), moduleFile.asPsi().asTrace())

    // Read the initial module file.
    // FIXME Reuse single read tree both for module building and minimal module building
    val minimalModule = tryReadMinimalModule(moduleFile) ?: return null

    // Read the whole module and used templates (including nested ones).
    val treesReadResult = readWithTemplates(moduleFile, types.moduleDeclaration, templatesCache)

    val modulePsiDir = pathResolver.toPsiDirectory(moduleFile.parent) ?: error("A module file necessarily has a parent")

    val preProcessedTree = mergeTrees(treesReadResult.trees)
        .configurePluginDefaults(moduleDir = modulePsiDir, product = minimalModule.module.product)

    val pathInheritance = PathInheritance(
        templateGraph = treesReadResult.templateGraph,
        rootPath = moduleFile.toNioPath(),
    )
    val combinedInheritance = minimalModule.platformsInheritance +
            pathInheritance +
            MainTestInheritance +
            DefaultInheritance
    val refiner = TreeRefiner(combinedInheritance)

    // TODO This should be done without refining somehow?
    // Create a common "preview" of module settings to finish dependent/reactive configuration.
    val preProcessedCommonSettings: Settings = run {
        val preProcessedCommonTree = refiner.refineTree(preProcessedTree, setOf(moduleCtx))[Module::settings]
                as RefinedMappingNode
        preProcessedCommonTree.completeTree()?.instance() ?: return null
    }
    val effectiveCatalog = preProcessedCommonSettings.builtInCatalog() + projectVersionsCatalog

    // Finish preparing the tree with the dependent/reactive configuration.
    val processedTree = preProcessedTree
        .substituteCatalogDependencies(effectiveCatalog)
        .substituteComposeOsSpecific()  // must be after catalog substitution
        .configureSpringBootDefaults(preProcessedCommonSettings.springBoot)
        .configureLombokDefaults(preProcessedCommonSettings.lombok)

    // Create a common `Module` schema node needed later for `AmperModule` configuration.
    val processedCommonTree = refiner.refineTree(processedTree, setOf(moduleCtx))
    val commonModule = processedCommonTree.completeTree()?.instance<Module>()
        ?: return null

    // safe: `plugins` has a default
    val pluginsTree = processedCommonTree[Module::plugins] as RefinedMappingNode

    TreeDiagnosticFactories.forEach { diagnosticFactory ->
        diagnosticFactory.analyze(processedTree, minimalModule.module, problemReporter)
    }

    return ModuleBuildCtx(
        moduleFile = moduleFile,
        mergedTree = processedTree,
        refiner = refiner,
        catalog = effectiveCatalog,
        moduleCtxModule = commonModule,
        pluginsTree = pluginsTree,
        pathResolver = pathResolver,
        problemReporter = problemReporter,
    )
}

/**
 * Build and resolve internal module dependencies.
 */
context(_: ProblemReporter, _: SchemaTypingContext, _: AmperFrontendProjectRoot)
private fun buildAmperModules(
    modules: List<ModuleBuildCtx>,
): List<ModuleBuildCtx> {
    val dir2module = modules.associate { it.moduleFile.parent.toNioPath() to it.module }
    val reportedUnresolvedModules = mutableSetOf<Trace>()

    modules.forEach { module ->
        // Do build seeds and propagate settings.
        val seeds = module.moduleCtxModule.buildFragmentSeeds()

        val moduleFragments = createFragments(seeds, module) {
            it.resolveInternalDependency(
                dir2module,
                reportedUnresolvedModules,
                module.moduleFile.parent.toNioPath(),
            )
        }

        diagnoseSwiftPMDependencyInNonApplePlatform(moduleFragments = moduleFragments)

        val [leaves, testLeaves] = moduleFragments.filterIsInstance<DefaultLeafFragment>().partition { !it.isTest }

        module.module.apply {
            mavenPluginSettings = module.moduleCtxModule.mavenPlugins
            fragments = moduleFragments
            artifacts = createArtifacts(false, module.module.type, leaves) +
                    createArtifacts(true, module.module.type, testLeaves)
        }
    }

    return modules
}

private fun createArtifacts(
    isTest: Boolean,
    productType: ProductType,
    fragments: List<DefaultLeafFragment>,
): List<DefaultArtifact> = when (productType) {
    ProductType.KMP_LIB -> listOf(DefaultArtifact(if (!isTest) "lib" else "testLib", fragments, isTest))
    else -> fragments.map { DefaultArtifact(it.name, listOf(it), isTest) }
}

/**
 * Resolve internal modules against known ones by path.
 */
context(_: ProblemReporter, _: AmperFrontendProjectRoot)
private fun Dependency.resolveInternalDependency(
    moduleDir2module: Map<Path, AmperModule>,
    reportedUnresolvedModules: MutableSet<Trace>,
    resolvingModulePath: Path,
): Notation? = when (this) {
    is ExternalMavenDependency -> MavenDependency(
        coordinates = toMavenCoordinates(),
        trace = trace,
        compile = scope.compile,
        runtime = scope.runtime,
        exported = exported,
    )
    is InternalDependency -> resolveModuleDependency(moduleDir2module, reportedUnresolvedModules)
    is org.jetbrains.amper.frontend.schema.BomDependency -> BomDependency(
        // We can safely caset here, because catalogs were substituted.
        coordinates = (bom as UnscopedExternalMavenDependency).toMavenCoordinates(),
        trace = trace,
    )
    is org.jetbrains.amper.frontend.schema.RemoteSwiftPMDependencySchema -> RemoteSwiftPMDependencyNotation(
        swiftPMDependency = swiftPackage.convertSchema(),
        trace = trace,
    )
    is org.jetbrains.amper.frontend.schema.LocalSwiftPMDependencySchema -> LocalSwiftPMDependencyNotation(
        swiftPMDependency = localSwiftPackage.convertSchema(resolvingModulePath),
        trace = trace,
    )
    is CatalogDependency -> error("Catalog dependency must be processed earlier!")
}

context(problemReporter: ProblemReporter, projectRoot: AmperFrontendProjectRoot)
private fun InternalDependency.resolveModuleDependency(
    moduleDir2module: Map<Path, AmperModule>,
    reportedUnresolvedModules: MutableSet<Trace>,
): DefaultScopedNotation? {
    val module = moduleDir2module[path]
    if (module == null) {
        // Do not report the same error twice from different fragments.
        if (reportedUnresolvedModules.add(trace)) {
            val possibleCorrectPath = moduleDir2module.keys
                .find { it.name == path.name }
                ?.relativeTo(projectRoot.path)

            problemReporter.reportMessage(
                UnresolvedModuleDependency(
                    dependency = this,
                    projectRoot = projectRoot.path,
                    possibleCorrectPath = possibleCorrectPath
                )
            )
        }
        return null
    }

    return DefaultLocalModuleDependency(
        module = module,
        path = path,
        trace = trace,
        compile = scope.compile,
        runtime = scope.runtime,
        exported = exported,
    )
}
