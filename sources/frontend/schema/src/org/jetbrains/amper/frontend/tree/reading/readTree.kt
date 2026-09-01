/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.childrenOfType
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.aomBuilder.asPsi
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.Context
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.messages.getOriginalFilePath
import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.diagnoseDeprecatedProperties
import org.jetbrains.amper.frontend.tree.diagnoseUnknownProperties
import org.jetbrains.amper.frontend.tree.swiftpm.diagnoseTestOnlySwiftPMDependencies
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Read a tree from the given [file], using the [type] as the schema.
 *
 * @param unknownPropertiesMode whether to report unknown properties inside object mappings.
 * @param referenceParsingMode how to treat Amper references (`${...}`) syntax in the file. See [ReferencesParsingMode].
 * @param parseContexts whether to treat `@<id>` at the end of the object keys as contexts.
 * @param contexts the contexts of the whole file, e.g., a template context.
 */
@UsedInIdePlugin
context(_: ProblemReporter, _: FrontendPathResolver, projectRoot: AmperFrontendProjectRoot)
fun readTree(
    file: YAMLFile,
    type: SchemaType.ObjectType,
    vararg contexts: Context,
    unknownPropertiesMode: UnknownPropertiesParsingMode = UnknownPropertiesParsingMode.BestEffortAndReport,
    referenceParsingMode: ReferencesParsingMode = ReferencesParsingMode.IgnoreButWarn,
    parseContexts: Boolean = true,
): MappingNode {
    val rootContexts = contexts.toSet()
    return file.childrenOfType<YAMLDocument>().firstOrNull()?.topLevelValue?.let {
        val config = ParsingConfig(
            rootPath = projectRoot.path,
            basePath = file.getOriginalFilePath().parent.absolute(),
            unknownPropertiesMode = unknownPropertiesMode,
            supportContexts = parseContexts,
            referenceParsingMode = referenceParsingMode,
        )
        context(config, rootContexts) {
            parseFile(
                file = file,
                type = type,
            )
        }
    } ?: MappingNode(emptyList(), type.declaration, file.asTrace(), rootContexts)
}

context(_: ProblemReporter, _: FrontendPathResolver, _: AmperFrontendProjectRoot)
internal fun readTree(
    file: VirtualFile,
    declaration: SchemaObjectDeclaration,
    vararg contexts: Context,
    unknownPropertiesMode: UnknownPropertiesParsingMode = UnknownPropertiesParsingMode.BestEffortAndReport,
    referenceParsingMode: ReferencesParsingMode = ReferencesParsingMode.IgnoreButWarn,
    parseContexts: Boolean = true,
): MappingNode {
    val psiFile = file.asPsi()
    return ApplicationManager.getApplication().runReadAction(Computable {
        when (psiFile.language) {
            is YAMLLanguage -> readTree(
                file = psiFile as YAMLFile,
                type = declaration.toType(),
                contexts = contexts,
                unknownPropertiesMode = unknownPropertiesMode,
                referenceParsingMode = referenceParsingMode,
                parseContexts = parseContexts,
            )
            else -> error("Unsupported language: ${psiFile.language}")
        }
    })
}

internal class ParsingConfig(
    val rootPath: Path,
    val basePath: Path,
    val unknownPropertiesMode: UnknownPropertiesParsingMode,
    val referenceParsingMode: ReferencesParsingMode,
    val supportContexts: Boolean,
)

context(_: Contexts, config: ParsingConfig, reporter: ProblemReporter)
private fun parseFile(
    file: YAMLFile,
    type: SchemaType.ObjectType,
): MappingNode? {
    val documents = file.childrenOfType<YAMLDocument>()
    if (documents.size > 1) {
        reporter.reportBundleError(
            source = documents[1].asBuildProblemSource(),
            diagnosticId = TreeDiagnosticId.MultipleYAMLDocumentsAreNotSupported,
            messageKey = "validation.structure.unsupported.multiple.documents"
        )
    }
    val value = documents.first() // Safe - at least one document is always present
        .topLevelValue ?: return null
    val resultNode = parseNode(YamlValue(value, tag = null), type)
    diagnoseTestOnlySwiftPMDependencies(resultNode)
    if (config.reportUnknownProperties) {
        diagnoseUnknownProperties(resultNode)
    }
    diagnoseDeprecatedProperties(resultNode)
    return resultNode as? MappingNode?
}

enum class ReferencesParsingMode {
    /**
     * Neither parse/nor diagnose references.
     */
    Ignore,

    /**
     * Parse and fully validate references.
     */
    Parse,

    /**
     * We do not parse references as in "yield ReferenceTreeValue", but we diagnose them with warnings.
     * Suited for files where references are not yet supported but planned.
     */
    IgnoreButWarn,
}

enum class UnknownPropertiesParsingMode {
    /**
     * Do not include unknown properties in the tree and do not report them.
     */
    SkipSilently,

    /**
     * Parse unknown properties on the best effort basis but do not report them.
     */
    BestEffortSilently,

    /**
     * Parse unknown properties on the best effort basis and report them.
     */
    BestEffortAndReport,
}
