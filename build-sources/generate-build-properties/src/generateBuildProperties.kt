/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import org.eclipse.jgit.api.Git
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

@TaskAction(ExecutionAvoidance.Disabled)
fun generateBuildProperties(
    @Input dotGitPath: Path, // it won't track the whole directory because we disabled execution avoidance
    @Output taskOutputDirectory: Path,
    version: String?,
    classSimpleName: String,
    classPackage: String,
    addDocumentationUrl: Boolean,
) {
    checkNotNull(version) {
        "`settings.publishing.version` is required to be set for build properties"
    }
    val taskOutputDirectory = taskOutputDirectory.createDirectories()

    // .git is usually a directory, but can be a file in the case when `git worktree add` was used so exists check
    // is sufficient.
    check(dotGitPath.exists()) {
        "Git root doesn't exist: $dotGitPath"
    }

    val actualDotGitRepository = dotGitPath.parent

    // We run without global Git config to avoid issues with people who use config parameters that JGit does not
    // support. For example, the 'patience' diff algorithm isn't supported.
    val [commitHash, commitShortHash, commitDate] = runWithoutGlobalGitConfig {
        Git.open(actualDotGitRepository.toFile()).use { git ->
            val repo = git.repository
            val head = repo.refDatabase.getReflogReader("HEAD").lastEntry
            val shortHash = repo.newObjectReader().use { it.abbreviate(head.newId).name() }
            Triple(head.newId.name, shortHash, head.who.whenAsInstant.toString())
        }
    }

    val fileSpec = generateBuildInfoFile(version, commitHash, commitShortHash, commitDate, classSimpleName, classPackage, addDocumentationUrl)
    val content = fileSpec.toString().toByteArray()
    val outputDir = taskOutputDirectory.resolve(classPackage.replace('.', '/'))
    outputDir.createDirectories()
    writeContentIfChanged(outputDir.resolve("$classSimpleName.kt"), content)
}

private fun generateBuildInfoFile(
    version: String,
    commitHash: String,
    commitShortHash: String,
    commitDate: String,
    classSimpleName: String,
    classPackage: String,
    addDocumentationUrl: Boolean,
): FileSpec {
    val instantClass = ClassName("kotlin.time", "Instant")

    val isDevVersion = isDevVersion(version)
    val isSnapshot = isSnapshot(version)
    val majorAndMinorVersion = extractMajorAndMinorVersion(version)
    val userAgentVersion = extractVersionForUserAgent(version)
    val docUrl = documentationUrl(version)

    val buildInfoObject = TypeSpec.objectBuilder(classSimpleName)
        .addProperty(
            PropertySpec.builder("mavenVersion", String::class)
                .addModifiers(KModifier.CONST)
                .initializer("%S", version)
                .addKdoc("The current version of the Kotlin Toolchain as seen in Maven dependencies.")
                .build()
        )
        .addProperty(
            PropertySpec.builder("majorAndMinorVersion", String::class)
                .addModifiers(KModifier.CONST)
                .initializer("%S", majorAndMinorVersion)
                .addKdoc("The first two components of the version (e.g., \"0.9\" from \"0.9.1\").")
                .build()
        )
        .addProperty(
            PropertySpec.builder("userAgentVersion", String::class)
                .addModifiers(KModifier.CONST)
                .initializer("%S", userAgentVersion)
                .addKdoc("The version of the Kotlin Toolchain as seen in User Agent used for downloading external artifacts.")
                .build()
        )
        .addProperty(
            PropertySpec.builder("isSNAPSHOT", Boolean::class)
                .addModifiers(KModifier.CONST)
                .initializer("%L", isSnapshot)
                .build()
        )
        .addProperty(
            PropertySpec.builder("isDevVersion", Boolean::class)
                .addModifiers(KModifier.CONST)
                .initializer("%L", isDevVersion)
                .addKdoc("Whether current build is a development one.")
                .build()
        ).apply {
            if (addDocumentationUrl) {
                addProperty(
                    PropertySpec.builder("documentationUrl", String::class)
                        .addModifiers(KModifier.CONST)
                        .initializer("%S", docUrl)
                        .addKdoc(
                            """
                    URL to the Kotlin Toolchain documentation for this version.

                    Note: For dev versions, this always points to the latest dev documentation
                    even if a corresponding stable version has been released.
                    """.trimIndent()
                        )
                        .build()
                )
            }
        }.addProperty(
            PropertySpec.builder("commitHash", String::class)
                .addModifiers(KModifier.CONST)
                .initializer("%S", commitHash)
                .build()
        )
        .addProperty(
            PropertySpec.builder("commitShortHash", String::class)
                .addModifiers(KModifier.CONST)
                .initializer("%S", commitShortHash)
                .build()
        )
        .addProperty(
            PropertySpec.builder("commitInstant", instantClass)
                .initializer("%T.parse(%S)", instantClass, commitDate)
                .build()
        )
        .build()

    return FileSpec.builder(classPackage, classSimpleName)
        .addFileComment("THIS FILE IS AUTO-GENERATED. DO NOT EDIT MANUALLY.")
        .addType(buildInfoObject)
        .addAnnotation(
            // KotlinPoet generates everything with explicit public visibility
            AnnotationSpec.builder(Suppress::class)
                .addMember("%S", "REDUNDANT_VISIBILITY_MODIFIER")
                .build()
        )
        .build()
}

internal fun extractMajorAndMinorVersion(version: String): String =
    version.split("-")[0].split(".").take(2).joinToString(".")

internal fun extractPatchVersion(version: String): String? =
    version.split("-")[0].split(".").getOrNull(2)

private fun isDevVersion(version: String): Boolean = version.contains("-dev-")
private fun isSnapshot(version: String): Boolean = version.contains("-SNAPSHOT")

internal fun documentationUrl(version: String): String {
    val isDevVersion = isDevVersion(version)
    val isSnapshot = isSnapshot(version)
    val majorAndMinorVersion = extractMajorAndMinorVersion(version)
    return if (isDevVersion || isSnapshot) "https://kotlin-toolchain.org/dev" else "https://kotlin-toolchain.org/$majorAndMinorVersion"
}

internal fun extractVersionForUserAgent(version: String): String {
    return if (isSnapshot(version))
        "LOCAL-SNAPSHOT"
    else {
        val majorAndMinorVersion = extractMajorAndMinorVersion(version)
        val patchVersionSuffix = extractPatchVersion(version)?.let { ".$it" } ?: ""
        val devVersionSuffix = if(isDevVersion(version)) "-dev" else ""
        "$majorAndMinorVersion$patchVersionSuffix$devVersionSuffix"
    }
}

private fun writeContentIfChanged(file: Path, content: ByteArray) {
    if (file.exists()) {
        val existingContent = file.readBytes()
        if (content.contentEquals(existingContent)) return
    }

    file.writeBytes(content)
}
