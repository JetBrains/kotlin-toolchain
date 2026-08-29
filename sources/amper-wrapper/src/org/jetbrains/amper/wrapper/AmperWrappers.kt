/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.wrapper

import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.wrapper.AmperWrappers.generate
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectory
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readBytes
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

private data class AmperScript(
    val fileName: String,
    val templateName: String,
    val executable: Boolean = false,
)

object AmperWrappers {
    private val templateProvider = TemplateProvider getTemplate@ { name ->
        val stream = javaClass.classLoader.getResourceAsStream("wrappers/$name")
            ?: return@getTemplate null
        Template(
            name = name,
            text = stream.use { it.readAllBytes() }.decodeToString(),
        )
    }

    /**
     * Generates Amper wrapper scripts in the specified [targetDir].
     *
     * The wrappers are generated from the template resources, substituting the values of the [amperVersion] and
     * [amperDistTgzSha256].
     *
     * @return the paths to the generated wrapper scripts
     */
    fun generate(
        targetDir: Path,
        amperVersion: String,
        amperDistTgzSha256: String,
        includePosix: Boolean = true,
        includeWindows: Boolean = true,
    ): GeneratedWrappers {
        require(includePosix || includeWindows) { "Nothing to generate" }

        val macros = mapOf(
            "KOTLIN_TOOLCHAIN_VERSION" to amperVersion,
            "KOTLIN_TOOLCHAIN_DIST_TGZ_SHA256" to amperDistTgzSha256,
        )

        return GeneratedWrappers(
            wrapperSh = if (includePosix) {
                AmperScript(
                    fileName = "kotlin",
                    templateName = "wrapper.template.sh",
                    executable = true,
                ).generate(targetDir, macros)
            } else null,
            wrapperBat = if (includeWindows) {
                AmperScript(
                    fileName = "kotlin.bat",
                    templateName = "wrapper.template.bat",
                ).generate(targetDir, macros)
            } else null,
        )
    }

    /**
     * Generates all necessary launcher scripts in the [targetDir] directory.
     */
    fun generateLaunchers(
        targetDir: Path,
    ) {
        targetDir.createDirectory()

        // Common launcher script that is called directly on Linux/macOS and via the busybox-w32 on Windows.
        AmperScript(
            fileName = "launcher.sh",
            templateName = "launcher.template.sh",
            executable = true,
        ).generate(targetDir, emptyMap())
    }

    /**
     * Generates installer scripts in the specified [targetDir].
     * Installer is generated for each present wrapper in [wrappers].
     *
     * @param amperVersion the version to bake into the installer
     * @param wrappers wrappers generated beforehand using [generate].
     */
    fun generateInstallers(
        targetDir: Path,
        amperVersion: String,
        wrappers: GeneratedWrappers,
    ) {
        val commonMacros = mapOf(
            "KOTLIN_TOOLCHAIN_VERSION" to amperVersion,
        )
        if (wrappers.wrapperSh != null) {
            AmperScript(
                fileName = "installer.sh",
                templateName = "installer.template.sh",
                executable = true,
            ).generate(
                targetDir = targetDir,
                macroSubstitutions = commonMacros + mapOf(
                    "KOTLIN_CLI_WRAPPER_SHA256" to wrappers.wrapperSh.sha256String(),
                ),
            )
        }
        if (wrappers.wrapperBat != null) {
            AmperScript(
                fileName = "installer.ps1",
                templateName = "installer.template.ps1",
            ).generate(
                targetDir = targetDir,
                macroSubstitutions = commonMacros + mapOf(
                    "KOTLIN_CLI_WRAPPER_SHA256" to wrappers.wrapperBat.sha256String(),
                ),
            )
        }
    }

    data class GeneratedWrappers(
        val wrapperSh: Path?,
        val wrapperBat: Path?,
    )

    private fun AmperScript.generate(
        targetDir: Path,
        macroSubstitutions: Map<String, String>,
    ): Path {
        val path = targetDir.resolve(fileName)

        val template = checkNotNull(templateProvider.getTemplate(templateName)) {
            "template script not found: $templateName"
        }
        path.writeText(
            template.substitute(
                macroSubstitutions = macroSubstitutions,
                templateProvider = templateProvider,
            )
        )

        if (executable && path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            @Suppress("RETURN_VALUE_NOT_USED") // KT-86696
            path.setPosixFilePermissions(path.getPosixFilePermissions() + PosixFilePermission.OWNER_EXECUTE)
        }
        return path
    }
}
