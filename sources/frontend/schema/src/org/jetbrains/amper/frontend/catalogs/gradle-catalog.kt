/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.catalogs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.childrenOfType
import org.jetbrains.amper.frontend.CompositeVersionCatalog
import org.jetbrains.amper.frontend.FileVersionCatalog
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.asTrace
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.tree.reading.maven.validateAndReportMavenCoordinates
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.toml.lang.psi.TomlArrayTable
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind

private val TomlKey.path: List<String>
    get() = segments.map { it.name.orEmpty() }

private fun TomlFile.findTableOrNull(name: String): TomlTable? =
    childrenOfType<TomlTable>().firstOrNull { it.header.key?.path == [name] }

private data class LibraryField(val path: List<String>, val entry: TomlKeyValue)

private data class CatalogLibrary(
    val alias: String,
    val aliasElement: PsiElement,
    val element: PsiElement,
    val notation: TomlKeyValue? = null,
    val fields: List<LibraryField> = [],
) {
    fun field(name: String): TomlKeyValue? = fields.firstOrNull { it.path == name.split('.') }?.entry
    fun string(name: String): String? = field(name)?.value.stringValueOrNull()
    val catalogKey: String get() = "libs." + alias.replace('-', '.').replace('_', '.')
}

private fun libraryFields(entries: List<TomlKeyValue>, prefix: List<String> = []): List<LibraryField> =
    entries.flatMap { libraryFields(it, prefix + it.key.path) }

private fun libraryFields(entry: TomlKeyValue, path: List<String>): List<LibraryField> {
    val versionTable = entry.value as? TomlInlineTable
    return if (path == ["version"] && versionTable != null && versionTable.entries.isNotEmpty()) {
        libraryFields(versionTable.entries, path)
    } else {
        [LibraryField(path, entry)]
    }
}

private data class TomlLibraryDefinition(
    val libraryString: String,
    val element: PsiElement,
)

private class TomlCatalog(
    override val location: VirtualFile,
    private val libraries: Map<String, TomlLibraryDefinition>,
    val problems: List<BuildProblem>,
) : FileVersionCatalog {
    override val entries: Map<String, TraceableString>
        get() = libraries.map {
            val definition = it.value
            it.key to TraceableString(definition.libraryString, trace = definition.element.asTrace())
        }.toMap()
}

private class DottedCatalogAlias(
    override val element: PsiElement,
    private val replacement: String,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val diagnosticId = FrontendDiagnosticId.DottedCatalogAlias
    override val message: String
        get() = SchemaBundle.message(
            "catalog.library.alias.dotted",
            element.text,
            replacement,
        )
}

private class CatalogProblem(
    override val element: PsiElement,
    override val diagnosticId: FrontendDiagnosticId,
    messageKey: String,
    vararg parameters: Any,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val message: String = SchemaBundle.message(messageKey, *parameters)
}

/** Reports invalid aliases once when the project model is read, including unused entries. */
context(problemReporter: ProblemReporter)
internal fun VersionCatalog?.reportCatalogProblems() {
    when (this) {
        is TomlCatalog -> problems.forEach(problemReporter::reportMessage)
        is CompositeVersionCatalog -> catalogs.forEach { it.reportCatalogProblems() }
        else -> Unit
    }
}

/**
 * A gradle compliant version catalog, that supports only:
 * 1. `[versions]` and `[libraries]` sections, no `[plugins]` or `[bundles]`
 * 2. versions or version refs, no version constraints
 */
internal fun FrontendPathResolver.parseGradleVersionCatalog(
    catalogFile: VirtualFile,
    conflictingCatalogFile: VirtualFile? = null,
): VersionCatalog? {
    val psiFile = toPsiFile(catalogFile) as? TomlFile ?: return null
    val reporter = CollectingProblemReporter()
    if (conflictingCatalogFile != null) {
        reporter.reportMessage(CatalogProblem(
            toPsiFile(conflictingCatalogFile) ?: psiFile,
            FrontendDiagnosticId.MultipleCatalogFiles,
            "catalog.files.multiple",
            catalogFile.path,
            conflictingCatalogFile.path,
        ))
    }
    with(reporter) { validateCatalogToml(psiFile) }
    if (reporter.problems.isNotEmpty()) return TomlCatalog(catalogFile, emptyMap(), reporter.problems)
    val libraries = with(reporter) {
        psiFile.validateExpandedSections()
        psiFile.findTableOrNull("versions")?.validateVersionTypes()
        psiFile.findTableOrNull("versions")?.validateVersionConstraints()
        psiFile.parseCatalogLibraries()
    }
    return TomlCatalog(
        location = catalogFile,
        libraries = libraries,
        problems = reporter.problems,
    )
}

/** Reads inline, dotted-property, and table definitions while preserving their original PSI sources. */
context(problemReporter: ProblemReporter)
private fun TomlFile.readLibraries(): List<CatalogLibrary> {
    val entries = findTableOrNull("libraries")?.entries.orEmpty()
    val simple = entries.filter { it.key.path.size == 1 }.mapNotNull { entry ->
        val alias = entry.key.path.single()
        if ('.' in alias) {
            reportDottedAlias(entry.key)
            null
        } else {
            val table = entry.value as? TomlInlineTable
            if (table == null) CatalogLibrary(alias, entry.key, entry, notation = entry)
            else CatalogLibrary(alias, entry.key, entry, fields = libraryFields(table.entries))
        }
    }
    val properties = entries.filter { it.key.path.size > 1 && isLibraryProperty(it.key) }
    for (entry in entries.filter { it.key.path.size > 1 && !isLibraryProperty(it.key) }) {
        reportDottedAlias(entry.key)
    }
    val expanded = properties.groupBy { it.key.path.first() }.map { [alias, fields] ->
        CatalogLibrary(
            alias, fields.first().key.segments.first(), fields.first(),
            fields = fields.flatMap { libraryFields(it, it.key.path.drop(1)) },
        )
    }
    val tables = childrenOfType<TomlTable>().filter { it.header.key?.path?.firstOrNull() == "libraries" }
    val nested = tables.filter { it.header.key?.path?.size != 1 }.mapNotNull { readLibraryTable(it) }
    return simple + expanded + nested
}

context(_: ProblemReporter)
private fun readLibraryTable(table: TomlTable): CatalogLibrary? {
    val key = table.header.key ?: return null
    val path = key.path.drop(1)
    if ('.' in path.first() || path.size > 2 || (path.size == 2 && path.last() != "version")) {
        reportDottedAlias(key, skip = 1)
        return null
    }
    return CatalogLibrary(path.first(), key.segments[1], table, fields = libraryFields(table.entries, path.drop(1)))
}

private fun isLibraryProperty(key: TomlKey): Boolean {
    val path = key.path
    if (path.any { '.' in it }) return false
    val field = path.drop(1)
    return (field.size == 1 && field.single() in ["module", "group", "name", "version"]) ||
            (field.size == 2 && field.first() == "version")
}

context(problemReporter: ProblemReporter)
private fun reportDottedAlias(key: TomlKey, skip: Int = 0) {
    val aliasSegments = key.segments.drop(skip)
    val segments = if ('.' in aliasSegments.first().name.orEmpty()) [aliasSegments.first()] else aliasSegments
    val element = if (segments.size == 1) segments.single() else key
    problemReporter.reportMessage(DottedCatalogAlias(
        element,
        segments.joinToString("-") { it.name.orEmpty().replace('.', '-') },
    ))
}

context(problemReporter: ProblemReporter)
private fun TomlFile.parseCatalogLibraries(): Map<String, TomlLibraryDefinition> {
    val aliasesByKey = readLibraries().groupBy { it.catalogKey }
    return buildMap {
        for ([key, libraries] in aliasesByKey) {
            val aliases = libraries.map { it.alias }.distinct()
            if (aliases.size > 1) {
                for (library in libraries) {
                    problemReporter.reportMessage(CatalogProblem(
                        library.aliasElement,
                        FrontendDiagnosticId.CatalogAliasCollision,
                        "catalog.library.alias.collision",
                        aliases.joinToString(", "),
                        key,
                    ))
                }
                continue
            }
            val library = libraries.first().copy(fields = libraries.flatMap { it.fields })
            if (!validateLibraryConstraints(library) || !validateLibraryTypes(library) || !validateLibraryFields(library)) continue
            val value = getInlineNotation(library) ?: continue
            if (!validateCatalogCoordinates(library.notation?.value ?: library.element, value)) continue
            put(key, TomlLibraryDefinition(value, library.element))
        }
    }
}

context(problemReporter: ProblemReporter)
private fun getInlineNotation(library: CatalogLibrary): String? {
    if (library.notation != null) return library.notation.value.stringValueOrNull()
    val module = library.string("module")
    val group = library.string("group")
    val name = library.string("name")
    val moduleName = module ?: if (group != null && name != null) "$group:$name" else return null
    if (!validateCatalogCoordinates(library.field("module")?.value ?: library.element, moduleName, moduleOnly = true)) return null

    val version = library.string("version")
    val versionRef = library.string("version.ref")
    if (version == null && versionRef == null) return moduleName // The version may come from a BOM.
    val finalVersion = version ?: resolveVersion(library, versionRef ?: return null) ?: return null
    return "$moduleName:$finalVersion"
}

context(problemReporter: ProblemReporter)
private fun resolveVersion(library: CatalogLibrary, versionRef: String): String? {
    val file = library.element.containingFile as TomlFile
    val version = file.findTableOrNull("versions")?.entries?.firstOrNull { it.key.path == [versionRef] }
    if (version == null) {
        problemReporter.reportMessage(CatalogProblem(
            library.field("version.ref")?.value ?: library.element,
            FrontendDiagnosticId.UnresolvedCatalogVersion,
            "catalog.version.ref.unresolved",
            versionRef,
        ))
    }
    return version?.value.stringValueOrNull()
}

context(problemReporter: ProblemReporter)
private fun validateCatalogCoordinates(origin: PsiElement, coordinates: String, moduleOnly: Boolean = false): Boolean {
    if (!validateAndReportMavenCoordinates(origin, coordinates)) return false
    val notation = coordinates.split('@')
    val parts = notation.first().split(':')
    if (notation.size > 2 || notation.any { it.isBlank() } || parts.any { it.isBlank() } ||
        (moduleOnly && (parts.size != 2 || notation.size != 1))) {
        problemReporter.reportMessage(CatalogProblem(
            origin,
            FrontendDiagnosticId.InvalidCatalogCoordinates,
            "catalog.coordinates.invalid",
            coordinates,
            if (moduleOnly) "group:artifact" else "group:artifact[:version[:classifier]][@packaging]",
        ))
        return false
    }
    return true
}

private fun PsiElement?.isTomlString(): Boolean = this is TomlLiteral && kind is TomlLiteralKind.String

private fun PsiElement?.stringValueOrNull(): String? =
    ((this as? TomlLiteral)?.kind as? TomlLiteralKind.String)?.value

context(problemReporter: ProblemReporter)
private fun reportInvalidType(entry: TomlKeyValue, expected: String) {
    problemReporter.reportMessage(CatalogProblem(
        entry.value ?: entry,
        FrontendDiagnosticId.InvalidCatalogValueType,
        "catalog.value.type.invalid",
        entry.key.text,
        expected,
    ))
}

context(_: ProblemReporter)
private fun TomlTable.validateVersionTypes() {
    for (entry in entries) {
        if (!entry.value.isTomlString() && entry.value !is TomlInlineTable) {
            reportInvalidType(entry, "a string")
        }
    }
}

private val libraryFieldPaths: List<List<String>> =
    [["module"], ["group"], ["name"], ["version"], ["version", "ref"]]

context(_: ProblemReporter)
private fun validateLibraryTypes(library: CatalogLibrary): Boolean {
    val notation = library.notation
    if (notation != null) {
        if (notation.value.isTomlString()) return true
        reportInvalidType(notation, "a string or a library table")
        return false
    }
    val invalidFields = library.fields.filter { it.path in libraryFieldPaths && !it.entry.value.isTomlString() }
    for (field in invalidFields) reportInvalidType(field.entry, "a string")
    return invalidFields.isEmpty()
}

context(problemReporter: ProblemReporter)
private fun validateLibraryFields(library: CatalogLibrary): Boolean {
    if (library.notation != null) return true
    val unknownFields = library.fields.filter { it.path !in libraryFieldPaths }
    for (field in unknownFields) {
        problemReporter.reportMessage(CatalogProblem(
            field.entry.key,
            FrontendDiagnosticId.UnknownCatalogField,
            "catalog.field.unknown",
            field.entry.key.text,
        ))
    }
    val hasModule = library.field("module") != null || (library.field("group") != null && library.field("name") != null)
    if (!hasModule) {
        problemReporter.reportMessage(CatalogProblem(
            library.element,
            FrontendDiagnosticId.MissingCatalogModule,
            "catalog.library.module.missing",
            library.alias,
        ))
    }
    return unknownFields.isEmpty() && hasModule
}

context(problemReporter: ProblemReporter)
private fun reportUnsupportedConstraint(element: PsiElement) {
    problemReporter.reportMessage(CatalogProblem(
        element,
        FrontendDiagnosticId.UnsupportedCatalogVersionConstraint,
        "catalog.version.constraint.unsupported",
    ))
}

context(_: ProblemReporter)
private fun TomlTable.validateVersionConstraints() {
    for (entry in entries) {
        if (entry.value is TomlInlineTable || entry.key.path.size > 1) reportUnsupportedConstraint(entry.value ?: entry)
    }
}

context(_: ProblemReporter)
private fun validateLibraryConstraints(library: CatalogLibrary): Boolean {
    val constraints = library.fields.filter {
        (it.path.firstOrNull() == "version" && it.path.size > 1 && it.path != ["version", "ref"]) ||
                (it.path == ["version"] && it.entry.value is TomlInlineTable)
    }
    for (constraint in constraints) reportUnsupportedConstraint(constraint.entry.key)
    return constraints.isEmpty()
}

context(problemReporter: ProblemReporter)
private fun TomlFile.validateExpandedSections() {
    for (table in childrenOfType<TomlTable>()) {
        val key = table.header.key ?: continue
        if (key.path.firstOrNull() == "versions" && key.path.size > 1) reportUnsupportedConstraint(key)
    }
    for (table in childrenOfType<TomlArrayTable>()) {
        val key = table.header.key ?: continue
        if (key.path.firstOrNull() in ["libraries", "versions"]) {
            problemReporter.reportMessage(CatalogProblem(
                key,
                FrontendDiagnosticId.InvalidCatalogValueType,
                "catalog.value.type.invalid",
                key.text,
                "a table, not an array of tables",
            ))
        }
    }
}
