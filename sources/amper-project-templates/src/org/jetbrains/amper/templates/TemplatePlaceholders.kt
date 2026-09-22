/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.templates

/** A placeholder that must be substituted before a text template is written. */
enum class TemplatePlaceholder(val text: String, val fixedValue: String? = null) {
    /** The project id: Kotlin package, Android namespace and application id, and iOS bundle id. */
    ProjectId("{{PROJECT_ID}}"),

    /** The project name: its root directory name, Android app label, window and page titles, and iOS product name. */
    ProjectName("{{PROJECT_NAME}}"),

    /** The optional [AppDir] prefix of client module paths. */
    AppModuleDir("{{APP_MODULE_DIR}}"),

    /** The path from a client module to the project root. */
    ProjectRootFromAppModule("{{PROJECT_ROOT_FROM_APP_MODULE}}"),

    AndroidAppModuleName("{{ANDROID_APP_MODULE_NAME}}", AndroidAppModule),
    DesktopAppModuleName("{{DESKTOP_APP_MODULE_NAME}}", DesktopAppModule),
    IosAppModuleName("{{IOS_APP_MODULE_NAME}}", IosAppModule),
    WebAppModuleName("{{WEB_APP_MODULE_NAME}}", WebAppModule),
    SharedModuleName("{{SHARED_MODULE_NAME}}", SharedModule),
    CoreModuleName("{{CORE_MODULE_NAME}}", CoreModule),
    ServerModuleName("{{SERVER_MODULE_NAME}}", ServerModule),
}

/**
 * Substitutes the placeholders that project templates use for values chosen when a project is generated.
 *
 * Used by [TemplateFile.extractTo] and by the CLI's parameterized Compose Multiplatform generator.
 */
object TemplatePlaceholders {

    /**
     * Replaces placeholders in [text], the contents of the template file at [relativePath].
     *
     * [projectName] is escaped as required by the format of that file. [projectId] is used as-is: it must be a valid
     * dot-separated lowercase identifier, which needs no escaping in any of the formats used by the templates.
     * When [flattenAppDirectory] is true, client modules are generated directly in the project root.
     */
    fun substituteIn(
        text: String,
        relativePath: String,
        projectId: String,
        projectName: String,
        flattenAppDirectory: Boolean = false,
    ): String {
        val textWithFixedValues = TemplatePlaceholder.entries.fold(text) { result, placeholder ->
            placeholder.fixedValue?.let { result.replace(placeholder.text, it) } ?: result
        }
        val appModuleDir = if (flattenAppDirectory) "" else AppDir
        val projectRootFromAppModule = if (flattenAppDirectory) ".." else "../.."
        return textWithFixedValues.replace(TemplatePlaceholder.ProjectId.text, projectId)
            .replace(TemplatePlaceholder.AppModuleDir.text, appModuleDir)
            .replace(TemplatePlaceholder.ProjectRootFromAppModule.text, projectRootFromAppModule)
            .substituteProjectName(relativePath, projectName)
    }

    private fun String.substituteProjectName(relativePath: String, projectName: String): String {
        val placeholder = TemplatePlaceholder.ProjectName.text
        if (placeholder !in this) return this
        val escapeForFormat = ProjectNameEscapers[relativePath]
            ?: error("The '$placeholder' placeholder was found in '$relativePath', which has no known text format")
        return replace(placeholder, escapeForFormat(projectName))
    }

    /**
     * How to escape the project name in each template file that uses [TemplatePlaceholder.ProjectName].
     *
     * Keys are paths relative to the template root, so any file added with that placeholder must be registered here.
     */
    private val ProjectNameEscapers: Map<String, (String) -> String> = mapOf(
        "$AppDir$AndroidAppModule/res/values/strings.xml" to String::escapeForAndroidStringResource,
        "$AppDir$DesktopAppModule/src/main.kt" to String::escapeForKotlinStringLiteral,
        "$AppDir$IosAppModule/module.xcodeproj/project.pbxproj" to String::escapeForPbxQuotedString,
        "$AppDir$IosAppModule/module.xcodeproj/xcshareddata/xcschemes/app.xcscheme" to String::escapeForXml,
        "$AppDir$WebAppModule/resources/index.html" to String::escapeForXml,
    )
}

/** The client-module directory used when generated modules are not flattened into the project root. */
const val AppDir = "app/"

const val AndroidAppModule = "androidApp"
const val DesktopAppModule = "desktopApp"
const val IosAppModule = "iosApp"
const val WebAppModule = "webApp"
const val SharedModule = "shared"
const val CoreModule = "core"
const val ServerModule = "server"

/**
 * Whether the template file at [relativePath] must be copied verbatim, without placeholder substitution, because
 * decoding it as text would corrupt it.
 */
fun isBinaryTemplateFile(relativePath: String): Boolean =
    relativePath.substringAfterLast('.') in BinaryTemplateFileExtensions

private val BinaryTemplateFileExtensions: Set<String> = ["png"]

private fun String.escapeForAndroidStringResource(): String {
    val escaped = buildString(length) {
        val firstCharacter = this@escapeForAndroidStringResource.firstOrNull()
        if (firstCharacter == '@' || firstCharacter == '?') append('\\')
        for (character in this@escapeForAndroidStringResource) {
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(character)
            }
        }
    }
    return if (hasWhitespaceThatAndroidWouldCollapse()) "\"$escaped\"" else escaped
}

private fun String.hasWhitespaceThatAndroidWouldCollapse(): Boolean =
    firstOrNull()?.isWhitespace() == true ||
            lastOrNull()?.isWhitespace() == true ||
            zipWithNext().any { (first, second) -> first.isWhitespace() && second.isWhitespace() }

private fun String.escapeForKotlinStringLiteral(): String = buildString(length) {
    for (character in this@escapeForKotlinStringLiteral) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\${'$'}")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
}

private fun String.escapeForPbxQuotedString(): String = buildString(length) {
    for (character in this@escapeForPbxQuotedString) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append(character).append(character)
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
}

private fun String.escapeForXml(): String = buildString(length) {
    for (character in this@escapeForXml) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\t' -> append("&#9;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            else -> append(character)
        }
    }
}
