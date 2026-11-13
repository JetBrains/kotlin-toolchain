/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.EmptyVersionCatalog
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.catalogs.builtInCatalog
import org.jetbrains.amper.frontend.contexts.DefaultInheritance
import org.jetbrains.amper.frontend.contexts.MainTestInheritance
import org.jetbrains.amper.frontend.contexts.PathCtx
import org.jetbrains.amper.frontend.contexts.PathInheritance
import org.jetbrains.amper.frontend.contexts.plus
import org.jetbrains.amper.frontend.diagnostics.TemplateApplicationLoop
import org.jetbrains.amper.frontend.plus
import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.schema.Template
import org.jetbrains.amper.frontend.tree.ListNode
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.TreeRefiner
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.frontend.tree.instance
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.tree.reading.readTree
import org.jetbrains.amper.frontend.tree.traceableValue
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.graphs.depthFirstDetectLoops
import java.nio.file.Path

/**
 * Reads the effective [VersionCatalog] for the given [templateFile].
 *
 * This is a combination of the project catalog and the built-in catalogs provided by the enabled toolchains.
 */
@UsedInIdePlugin
context(_: ProblemReporter)
fun AmperProjectContext.readEffectiveCatalogForTemplate(templateFile: VirtualFile): VersionCatalog =
    context(
        frontendPathResolver,
        projectRoot,
        // We can use default typing context for this purpose because currently nothing can introduce catalogs apart
        // from builtin functionality. When bundled plugin templates are implemented, we'll need to pass the
        // plugin-aware context here.
        SchemaTypingContext(),
    ) {
        val (trees, templateGraph) = readWithTemplates(
            templateFile,
            contextOf<SchemaTypingContext>().templateDeclaration
        )
        val mergedTrees = mergeTrees(trees)
        val refiner = TreeRefiner(
            PathInheritance(templateGraph = templateGraph, rootPath = templateFile.toNioPath()) +
                    MainTestInheritance +
                    DefaultInheritance
        )
        val completeTemplate = refiner.refineTree(
            mergedTrees,
            listOf(PathCtx(templateFile.toNioPath(), templateFile.asPsi().asTrace()))
        ).completeTree()?.instance<Template>()
        val builtinCatalog = completeTemplate?.settings?.builtInCatalog() ?: EmptyVersionCatalog
        builtinCatalog + projectVersionsCatalog
    }

/**
 * Result of reading a module or template with all its templates (including nested ones).
 *
 * @param trees the source tree followed by all template trees applied in it
 * @param templateGraph the template application graph: maps each path to the set of template paths it applies directly
 */
internal data class ModuleTreesReadResult(
    val trees: List<MappingNode>,
    val templateGraph: Map<Path, Set<TraceablePath>>,
)

/**
 * Reads the given [file] (module or template) tree with as [fileDeclaration] and all its templates
 * (including nested ones).
 *
 * Templates are detected by recursively looking for `apply` list of paths in the [file] and all further templates.
 *
 * @return [ModuleTreesReadResult] containing all the trees and the graph of template applications
 * @see ModuleTreesReadResult
 */
context(_: ProblemReporter, types: SchemaTypingContext, _: FrontendPathResolver, _: AmperFrontendProjectRoot)
internal fun readWithTemplates(
    file: VirtualFile,
    fileDeclaration: SchemaObjectDeclaration,
    templatesCache: MutableMap<Path, MappingNode> = hashMapOf(),
): ModuleTreesReadResult {
    val filePath = file.toNioPath()
    val pathContext = PathCtx(filePath, file.asPsi().asTrace())
    val rootTree = readTree(file, fileDeclaration, pathContext)

    val templateEdges = mutableMapOf<Path, Set<TraceablePath>>()
    val allTemplateTrees = mutableListOf<MappingNode>()

    val queue = ArrayDeque<Path>()
    val startTemplates = rootTree.extractApplyPaths()
    templateEdges[filePath] = startTemplates.toSet()
    queue.addAll(startTemplates.map { it.value })

    val visitedTemplates = mutableSetOf<Path>()

    loop@ while (queue.isNotEmpty()) {
        val templatePath = queue.removeFirst()
        if (!visitedTemplates.add(templatePath)) continue

        // The cache may be shared between module files that are read concurrently, so the access to it is
        // synchronized to guarantee that every template is read (and diagnosed) only once.
        val templateTree = synchronized(templatesCache) {
            templatesCache.getOrPut(templatePath) {
                val templateVirtual = templatePath.asVirtualOrNull() ?: continue@loop
                val psiFile = templateVirtual.asPsiOrNull() ?: continue@loop
                readTree(templateVirtual, types.templateDeclaration, PathCtx(templatePath, psiFile.asTrace()))
            }
        }
        allTemplateTrees.add(templateTree)

        val appliedTemplates = templateTree.extractApplyPaths()
        if (appliedTemplates.isNotEmpty()) {
            templateEdges[templatePath] = appliedTemplates.toMutableSet()
            for (template in appliedTemplates) {
                if (template.value !in visitedTemplates) {
                    queue.add(template.value)
                }
            }
        }
    }

    analyzeAndReportTemplateLoops(filePath, templateEdges)

    return ModuleTreesReadResult(
        trees = listOf(rootTree) + allTemplateTrees,
        templateGraph = templateEdges,
    )
}

// TODO: If we knew the list of all project templates beforehand, we could read them once per project, detect loops
//  once, report them without the file context. However, currently, template only appears in the project when
//  it's applied, so we can detect loops only from modules.
//  N.B.: When highlighting the file, we might still want to mark templates participating in loops as an extra error
//  so it's visible in the IDE (or implement custom highlighting for references to files that have problems).
context(problemReporter: ProblemReporter)
private fun analyzeAndReportTemplateLoops(
    filePath: Path, // Path from which we analyze templates
    templateEdges: Map<Path, Set<TraceablePath>>,
) {
    val loops = depthFirstDetectLoops(roots = listOf(filePath)) { edge ->
        templateEdges[edge]?.map { it.value }.orEmpty()
    }
    loops.forEach { loop ->
        val traceableLoop = buildList {
            val loopPath = loop.plusElement(loop.first())
            loopPath.zipWithNext().forEach { [t1, t2] ->
                val t1Applications = templateEdges[t1] ?: return@forEach
                val application = t1Applications.find { it.value == t2 } ?: return@forEach
                add(application)
            }
        }

        problemReporter.reportMessage(TemplateApplicationLoop(loopStart = loop.first(), traceableLoop))
    }
}

/**
 * Extracts the paths from the `apply` section of a tree.
 *
 * We can't simply use `apply` accessor as it requires a complete tree so we're using raw tree access API.
 */
private fun MappingNode.extractApplyPaths(): List<TraceablePath> {
    val applyKeyValue = children.find { it.key == "apply" } ?: return emptyList()
    val listNode = applyKeyValue.value as? ListNode ?: return emptyList()
    return listNode.children.mapNotNull { (it as? PathNode)?.traceableValue }
}