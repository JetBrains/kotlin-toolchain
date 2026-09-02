/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compilerplugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

@Tag("cli-test-group-compilerplugins")
class DataframeTest : CliTestBase() {

    @Test
    fun dataframe() = runSlowTest {
        val projectRoot = testProject("dataframe")
        val result = runCli(projectDir = projectRoot, "run")
        result.assertStdoutContains("""
            fullName,htmlUrl,stars,topics,watchers,topicCount,kind
            JetBrains/Arend,https://github.com/JetBrains/Arend,562,[arend],562,1,Other
            JetBrains/JetBrainsMono,https://github.com/JetBrains/JetBrainsMono,6059,"[coding-font, font, ligatures, monospaced-font, programming-font, programming-ligatures]",6059,6,Other
            JetBrains/JetBrainsRuntime,https://github.com/JetBrains/JetBrainsRuntime,370,"[intellij-platform, jdk, openjdk]",370,3,Other
            JetBrains/Qodana,https://github.com/JetBrains/Qodana,335,"[ci-cd, code-inspection, qodana]",335,3,Other
            JetBrains/android,https://github.com/JetBrains/android,724,[],724,0,Other
            JetBrains/androidx,https://github.com/JetBrains/androidx,51,[],51,0,Other
            JetBrains/awesome-pycharm,https://github.com/JetBrains/awesome-pycharm,186,"[awesome, ide, pycharm, python, web-development]",186,5,Other
            JetBrains/colorSchemeTool,https://github.com/JetBrains/colorSchemeTool,290,[],290,0,Other
            JetBrains/godot-support,https://github.com/JetBrains/godot-support,108,[],108,0,Other
            JetBrains/golandtipsandtricks,https://github.com/JetBrains/golandtipsandtricks,96,[],96,0,Other
            JetBrains/gradle-changelog-plugin,https://github.com/JetBrains/gradle-changelog-plugin,142,"[changelog, gradle, gradle-changelog-plugin, gradle-plugin, intellij, intellij-platform, intellij-plugin, jetbrains]",142,8,IntelliJ
            JetBrains/gradle-grammar-kit-plugin,https://github.com/JetBrains/gradle-grammar-kit-plugin,54,"[bnf, gradle-plugin, intellij, jflex]",54,4,IntelliJ
            JetBrains/http-request-in-editor-spec,https://github.com/JetBrains/http-request-in-editor-spec,66,[],66,0,Other
            JetBrains/intellij-arend,https://github.com/JetBrains/intellij-arend,77,"[arend, intellij, intellij-plugin]",77,3,IntelliJ
            JetBrains/intellij-plugin-verifier,https://github.com/JetBrains/intellij-plugin-verifier,113,[],113,0,IntelliJ
            JetBrains/intellij-sbt,https://github.com/JetBrains/intellij-sbt,81,[],81,0,IntelliJ
            JetBrains/intellij-scala,https://github.com/JetBrains/intellij-scala,1066,"[intellij-idea, intellij-plugin, scala]",1066,3,IntelliJ
            JetBrains/intellij-scala-bundle,https://github.com/JetBrains/intellij-scala-bundle,93,"[education, intellij, scala]",93,3,IntelliJ
            JetBrains/jetbrains_guide,https://github.com/JetBrains/jetbrains_guide,145,[hacktoberfest],145,1,Other
            JetBrains/jetpad-projectional-open-source,https://github.com/JetBrains/jetpad-projectional-open-source,75,[],75,0,Other
        """.trimIndent())
    }
}