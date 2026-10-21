/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.jetbrains.fus.reporting.schema.EventsSchemeBuilder
import com.jetbrains.fus.reporting.schema.RegisteredLogDescriptionsProcessor
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.fus.schema.FusSchemaDescription
import org.jetbrains.amper.fus.schema.toJsonString
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val fusBuildNumber = System.getenv("KOTLIN_TOOLCHAIN_FUS_SCHEMA_BUILD_NUMBER")
    ?: AmperBuild.majorAndMinorVersion

@TaskAction
fun generateFusSchema(@Output outputSchemaFile: Path) {
    // Enable description registration to allow displaying it here
    RegisteredLogDescriptionsProcessor.descriptionRegistrationEnabled = true

    val eventsScheme = EventsSchemeBuilder.buildEventsScheme(
        buildNumber = fusBuildNumber,
        groups = FusSchemaDescription.groups,
        recorderDefinitions = FusSchemaDescription.recorderDefinitions,
    )
    outputSchemaFile.writeText(eventsScheme.toJsonString())
}

@TaskAction
fun checkFusSchema(
    // we don't want to depend on the generation task, it would defeat the purpose of the check
    @Input(inferTaskDependency = false) existingCheckedInSchemaFile: Path,
    @Output actualSchemaTempFile: Path,
) {
    generateFusSchema(actualSchemaTempFile)

    val checkedInText = existingCheckedInSchemaFile.readText()
    val actualSchemaText = actualSchemaTempFile.readText()
    if (checkedInText == actualSchemaText) {
        println("The FUS schema is up to date")
        actualSchemaTempFile.deleteExisting()
        return
    }
    val checkedInLines = checkedInText.lines()
    val actualLines = actualSchemaText.lines()
    val patch = DiffUtils.diff(checkedInLines, actualLines)
    if (patch.deltas.isEmpty()) {
        // there should be deltas since the full text comparison failed
        error("The FUS schema has incorrect line separators, please check gitattributes")
    }
    val diff = UnifiedDiffUtils.generateUnifiedDiff(
        /* originalFileName = */ existingCheckedInSchemaFile.pathString,
        /* revisedFileName = */ actualSchemaTempFile.pathString,
        /* originalLines = */ checkedInLines,
        /* patch = */ patch,
        /* contextSize = */ 3,
    )
    throw FusSchemaChanged(diffText = diff.joinToString("\n"))
}

private class FusSchemaChanged(diffText: String) : Exception(buildString {
    appendLine("The FUS schema was changed:")
    appendLine()
    appendLine(diffText)
    appendLine()
    appendLine("If this is intentional, run `kotlin do generateFusSchema` to re-generate, and commit the changes.")
})
