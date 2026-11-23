/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.AmperPlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.aomBuilder.ModuleBuildCtx
import org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.IsolatedPluginYamlDiagnosticsFactories
import org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.PluginYamlMissing
import org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.diagnosePluginSettingsClass
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.catalogs.substituteCatalogDependencies
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractKeyValuePsiElement
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.plugins.generated.ShadowCompilationArtifactKind
import org.jetbrains.amper.frontend.plugins.generated.ShadowResolutionScope
import org.jetbrains.amper.frontend.plugins.generated.ShadowSourcesKind
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.project.getTaskOutputRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.TreeRefiner
import org.jetbrains.amper.frontend.tree.add
import org.jetbrains.amper.frontend.tree.buildTree
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.frontend.tree.instance
import org.jetbrains.amper.frontend.tree.invoke
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.tree.put
import org.jetbrains.amper.frontend.tree.reading.ReferencesParsingMode
import org.jetbrains.amper.frontend.tree.reading.UnknownPropertiesParsingMode
import org.jetbrains.amper.frontend.tree.reading.readTree
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.anyErrorsReported
import org.jetbrains.amper.problems.reporting.plus
import kotlin.io.path.div
import kotlin.io.path.isRegularFile

class AmperPluginImpl(
    private val projectContext: AmperProjectContext,
    override val pluginModule: AmperModule,
    pluginData: PluginData,
    types: SchemaTypingContext,
    problemReporter: ProblemReporter,
) : AmperPlugin {
    override val id = pluginData.id
    private val pluginYamlDeclaration = types.pluginYamlDeclaration(id)

    // If this tree is null (due to errors), then the plugin will be NOP
    private val pluginTree: RefinedMappingNode? = context(problemReporter, projectContext) {
        parseAndDiagnosePluginTree(
            pluginModule = pluginModule,
            pluginYamlDeclaration = pluginYamlDeclaration,
            pluginData = pluginData,
        )
    }

    context(problemReporter: ProblemReporter)
    internal fun asAppliedTo(
        module: ModuleBuildCtx,
    ): PluginYamlRoot? = context(problemReporter) {
        if (pluginTree == null) return null
        val moduleRootDir = module.module.source.moduleDir
        val pluginConfiguration = module.pluginsTree[id.value] as? RefinedMappingNode
            ?: return@context null

        val enabled = (pluginConfiguration["enabled"] as? BooleanNode)?.value
        if (enabled != true) {
            reportExplicitValuesWhenDisabled(pluginConfiguration)
            return null
        }

        val taskDirs = (pluginTree[PluginYamlRoot::tasks] as? RefinedMappingNode)
            ?.refinedChildren
            ?.filterValues { it.value !is ErrorNode }
            ?.mapValues { [name, _] ->
                projectContext.getTaskOutputRoot(taskIdFor(module.module, name))
            }.orEmpty()

        // Build a tree with computed "reference-only" values.
        val selfDependency = buildTree(DeclarationOfShadowDependencyLocal) {
            modulePath(moduleRootDir)
        }
        val referenceValuesTree = buildTree(pluginYamlDeclaration) {
            pluginSettings(pluginConfiguration)
            module {
                name(module.module.userReadableName)
                rootDir(moduleRootDir)
                self(selfDependency)
                runtimeClasspath {
                    dependencies { add(selfDependency) }
                }
                compileClasspath {
                    dependencies { add(selfDependency) }
                    scope(ShadowResolutionScope.Compile)
                }
                kotlinJavaSources { from(selfDependency) }
                resources {
                    from(selfDependency)
                    kind(ShadowSourcesKind.Resources)
                }
                jar {
                    from(selfDependency)
                    kind(ShadowCompilationArtifactKind.Jar)
                }
                classes {
                    from(selfDependency)
                    kind(ShadowCompilationArtifactKind.Classes)
                }

                // TODO: This will not include non-common non-main configuration.
                settings(module.moduleCtxModule.settings.backingTree)
                // TODO: Maybe at include test-settings here also?
            }
            project {
                rootDir(projectContext.projectRoot.path)
            }
            tasks {
                for ([taskName, taskBuildRoot] in taskDirs) {
                    put[taskName] {
                        taskOutputDir(taskBuildRoot)
                    }
                }
            }
        }

        val mergedTree = mergeTrees(pluginTree, referenceValuesTree)
            .substituteCatalogDependencies(pluginModule.usedCatalog)
        TreeRefiner().refineTree(mergedTree, EmptyContexts)
            .completeTree()?.instance<PluginYamlRoot>()
    }

    fun taskIdFor(module: AmperModule, name: String) =
        TaskId.moduleTask(module, "$name@${id.value}")

    context(problemReporter: ProblemReporter)
    private fun reportExplicitValuesWhenDisabled(pluginConfiguration: RefinedMappingNode) {
        val explicitValues = pluginConfiguration.children
            .filterNot { it.trace.isDefault }
        if (explicitValues.isNotEmpty()) {
            val source = MultipleLocationsBuildProblemSource(
                explicitValues.mapNotNull { it.asBuildProblemSource() as? FileBuildProblemSource },
                groupingMessage = SchemaBundle.message("plugin.unexpected.configuration.when.disabled.grouping"),
            )
            problemReporter.reportBundleError(
                source = source,
                diagnosticId = PluginDiagnosticId.PluginNotEnabledButConfigured,
                messageKey = "plugin.unexpected.configuration.when.disabled", id.value,
                level = Level.Warning,
            )
        }
    }
}

context(problemReporter: ProblemReporter, projectContext: AmperProjectContext)
private fun parseAndDiagnosePluginTree(
    pluginModule: AmperModule,
    pluginYamlDeclaration: SchemaObjectDeclaration,
    pluginData: PluginData,
): RefinedMappingNode? {
    diagnosePluginSettingsClass(pluginData, pluginModule.commonModuleNode.pluginInfo!!)

    val pluginFile = run { // Locate plugin.yaml
        val pluginFilepath = pluginModule.source.moduleDir / "plugin.yaml"
        if (!pluginFilepath.isRegularFile()) {
            problemReporter.reportMessage(
                PluginYamlMissing(
                    source = PsiBuildProblemSource(
                        pluginModule.commonModuleNode.product.typeDelegate.extractKeyValuePsiElement()
                    ),
                    expectedPluginYamlPath = pluginFilepath,
                )
            )
            return null
        }
        projectContext.frontendPathResolver.loadVirtualFile(pluginFilepath)
    }

    val collecting = CollectingProblemReporter()
    context(collecting + problemReporter, projectContext.frontendPathResolver, projectContext.projectRoot) {
        val tree = readTree(
            file = pluginFile,
            declaration = pluginYamlDeclaration,
            // Silently, because we report them later after refine and reference resolution
            unknownPropertiesMode = UnknownPropertiesParsingMode.BestEffortSilently,
            referenceParsingMode = ReferencesParsingMode.Parse,
            parseContexts = false,
        )

        val refinedTree = TreeRefiner().refineTree(tree, EmptyContexts)

        for (diagnosticsFactory in IsolatedPluginYamlDiagnosticsFactories) {
            diagnosticsFactory.analyze(refinedTree)
        }

        if (collecting.anyErrorsReported) {
            // If errors are detected, don't save the tree. No point in applying the plugin with errors.
            return null
        }

        return refinedTree
    }
}
