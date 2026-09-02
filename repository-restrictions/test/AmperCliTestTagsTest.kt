/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.test.Dirs
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.fail

private const val CliTestModuleName = "amper-cli-test"
private const val GroupTagPrefix = "cli-test-group-"

class AmperCliTestTagsTest {

    /**
     * The tags are checked at the level of the source files, so that we don't have to parse Kotlin declarations.
     */
    @Test
    fun `CLI test classes are tagged with the group matching their package`() {
        val violations = cliTestFiles().filter { it.groupTags != setOf(it.expectedGroupTag) }
        if (violations.isNotEmpty()) {
            val violationsList = violations.joinToString("\n") { testFile ->
                "  - ${testFile.location}: expected @Tag(\"${testFile.expectedGroupTag}\") " +
                        "but found ${testFile.foundGroupTagsDescription}"
            }
            fail(
                "Every test class in the '$CliTestModuleName' module must be annotated with " +
                        "@Tag(\"$GroupTagPrefix<group>\"), where <group> is the last segment of the package of that " +
                        "class. This is used to split CLI tests into groups on CI.\n" +
                        "The following files declare tests without the expected tag:\n\n$violationsList"
            )
        }
    }

    private fun cliTestFiles(): List<KotlinFile> {
        val testFiles = cliTestSourceRoots()
            .flatMap { it.walk() }
            .filter { it.extension == "kt" }
            .map(::KotlinFile)
            .filter { it.declaresTests }
        check(testFiles.isNotEmpty()) {
            "No file declaring tests was found in the '$CliTestModuleName' module, which is suspicious. " +
                    "Please check how this test detects tests and tags."
        }
        return testFiles
    }

    private fun cliTestSourceRoots(): List<Path> {
        val module = readAmperProjectModel().modules.find { it.userReadableName == CliTestModuleName }
            ?: error("Module '$CliTestModuleName' not found, please update this test if the module was renamed")
        return module.fragments.filter(Fragment::isTest).flatMap { it.sourceRoots }
    }
}

/**
 * A Kotlin source file, seen through the lens of the test declarations and tags it contains.
 */
private class KotlinFile(private val path: Path) {

    private val text = path.readText()

    val location: String
        get() = path.relativeTo(Dirs.amperCheckoutRoot).toString()

    val declaresTests: Boolean
        get() = testAnnotationRegex.containsMatchIn(text)

    /** The distinct `cli-test-group-*` tags used in this file. */
    val groupTags: Set<String>
        get() = tagAnnotationRegex.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.startsWith(GroupTagPrefix) }
            .toSet()

    val foundGroupTagsDescription: String
        get() = if (groupTags.isEmpty()) "no group tag" else groupTags.joinToString(", ") { "@Tag(\"$it\")" }

    val expectedGroupTag: String
        get() = GroupTagPrefix + packageName.substringAfterLast('.')

    private val packageName: String
        get() = packageRegex.find(text)?.groupValues?.get(1)
            ?: error("Cannot find the package declaration in $location")
}

private val packageRegex = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

private val tagAnnotationRegex = Regex("""@Tag\("([^"]*)"\)""")

private val testAnnotationRegex = Regex("""@(Test|ParameterizedTest|TestFactory|TestTemplate)\b""")
