/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.catalogs

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.toml.lang.lexer.parseTomlStringCharacters
import org.toml.lang.psi.TOML_BASIC_STRINGS
import org.toml.lang.psi.TOML_STRING_LITERALS
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlKeyValueOwner
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.ext.TomlLiteralKind

private class InvalidCatalogToml(
    override val element: PsiElement,
    description: String,
) : PsiBuildProblem(Level.Error, BuildProblemType.Generic) {
    override val diagnosticId = FrontendDiagnosticId.InvalidCatalogToml
    override val message: String = SchemaBundle.message("catalog.toml.invalid", description)
}

context(reporter: ProblemReporter)
internal fun validateCatalogToml(file: TomlFile) {
    val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
    for (error in errors) reporter.reportMessage(InvalidCatalogToml(error, error.errorDescription))
    val strings: List<PsiElement> = PsiTreeUtil.collectElementsOfType(file, TomlLiteral::class.java).toList() +
            PsiTreeUtil.collectElementsOfType(file, TomlKeySegment::class.java)
    val stringsValid = strings.map { validateString(it) }.all { it }
    if (errors.isNotEmpty() || !stringsValid) return
    val tables = file.childrenOfType<TomlTable>()
    val declaredTables = mutableSetOf<List<String?>>()
    for (table in tables) {
        val key = table.header.key ?: continue
        if (!declaredTables.add(key.segments.map { it.name }) && !key.isCatalogKey()) {
            reporter.reportMessage(InvalidCatalogToml(key, "Table ${key.text} is already defined"))
        }
    }
    validateCrossTableDefinitions(file)
    validateKeys(file.childrenOfType<TomlKeyValue>().filterNot { it.key.isCatalogKey() })
    for (owner in PsiTreeUtil.collectElementsOfType(file, TomlKeyValueOwner::class.java)) {
        // Catalog table definitions are already checked with their full paths, across table boundaries.
        if (owner is TomlTable && owner.header.key?.isCatalogKey() == true) continue
        validateKeys(owner.entries)
    }
}

private fun TomlKey.isCatalogKey(): Boolean = segments.firstOrNull()?.name in ["libraries", "versions"]

context(reporter: ProblemReporter)
private fun validateString(element: PsiElement): Boolean {
    val node = element.node.findChildByType(TOML_STRING_LITERALS) ?: return true
    val string = TomlLiteralKind.fromAstNode(node) as? TomlLiteralKind.String ?: return true
    if (string.offsets.closeDelim == null) {
        reporter.reportMessage(InvalidCatalogToml(element, "Unterminated string"))
        return false
    }
    if (node.elementType in TOML_BASIC_STRINGS) {
        val contents = string.offsets.value?.substring(node.text).orEmpty()
        if (!parseTomlStringCharacters(node.elementType, contents, StringBuilder()).second) {
            reporter.reportMessage(InvalidCatalogToml(element, "Invalid string escape"))
            return false
        }
    }
    return true
}

context(reporter: ProblemReporter)
private fun validateKeys(entries: List<TomlKeyValue>) {
    val declared = mutableMapOf<List<String?>, TomlKey>()
    val descendants = mutableMapOf<List<String?>, TomlKey>()
    for (entry in entries) {
        val path = entry.key.segments.map { it.name }
        val existing = declared[path] ?: descendants[path] ?:
                (1 until path.size).firstNotNullOfOrNull { declared[path.take(it)] }
        if (existing != null) {
            reporter.reportMessage(InvalidCatalogToml(entry.key, "Key ${entry.key.text} conflicts with ${existing.text}"))
        }
        declared[path] = entry.key
        for (length in 1 until path.size) descendants.putIfAbsent(path.take(length), entry.key)
    }
}

/** Checks definitions across table boundaries without treating a table's implicit parents as redefinitions. */
context(reporter: ProblemReporter)
private fun validateCrossTableDefinitions(file: TomlFile) {
    val definitions = CatalogTomlDefinitions(reporter)
    for (entry in file.childrenOfType<TomlKeyValue>()) {
        if (entry.key.segments.firstOrNull()?.name in ["libraries", "versions"]) definitions.addEntry(entry, [])
    }
    for (table in file.childrenOfType<TomlTable>()) {
        val key = table.header.key ?: continue
        val path = key.segments.map { it.name.orEmpty() }
        if (path.firstOrNull() !in ["libraries", "versions"]) continue
        definitions.addTable(key, path)
        for (entry in table.entries) definitions.addEntry(entry, path)
    }
}

private enum class DefinitionKind { ImplicitTable, ExplicitTable, DottedTable, Value }

private data class TomlDefinition(val kind: DefinitionKind, val key: TomlKey)

private class CatalogTomlDefinitions(private val reporter: ProblemReporter) {
    private val definitions = mutableMapOf<List<String>, TomlDefinition>()

    fun addTable(key: TomlKey, path: List<String>) {
        for (length in 1 until path.size) {
            val prefix = path.take(length)
            val existing = definitions[prefix]
            if (existing?.kind == DefinitionKind.Value) reportConflict(key, existing.key)
            if (existing == null) definitions[prefix] = TomlDefinition(DefinitionKind.ImplicitTable, key)
        }
        val existing = definitions[path]
        if (existing != null && existing.kind != DefinitionKind.ImplicitTable) reportConflict(key, existing.key)
        definitions[path] = TomlDefinition(DefinitionKind.ExplicitTable, key)
    }

    fun addEntry(entry: TomlKeyValue, tablePath: List<String>) {
        val path = tablePath + entry.key.segments.map { it.name.orEmpty() }
        for (length in tablePath.size + 1 until path.size) {
            val prefix = path.take(length)
            val existing = definitions[prefix]
            if (existing != null && existing.kind in [DefinitionKind.Value, DefinitionKind.ExplicitTable]) {
                reportConflict(entry.key, existing.key)
            }
            if (existing == null) definitions[prefix] = TomlDefinition(DefinitionKind.DottedTable, entry.key)
        }
        val existing = definitions[path]
        if (existing != null) reportConflict(entry.key, existing.key)
        definitions[path] = TomlDefinition(DefinitionKind.Value, entry.key)
    }

    private fun reportConflict(key: TomlKey, previous: TomlKey) {
        reporter.reportMessage(InvalidCatalogToml(key, "Definition ${key.text} conflicts with ${previous.text}"))
    }
}
