/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.cli.terminal.interactiveMultiSelectList
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import org.jetbrains.amper.projectwizard.ComposeMultiplatformTargetPlatform
import org.jetbrains.amper.projectwizard.DefaultInteractiveComposeMultiplatformTargets
import org.jetbrains.amper.projectwizard.DefaultProjectId
import org.jetbrains.amper.projectwizard.GitInitResult
import org.jetbrains.amper.projectwizard.InvalidProjectIdException
import org.jetbrains.amper.projectwizard.NewProjectMode
import org.jetbrains.amper.projectwizard.checkValidProjectId
import org.jetbrains.amper.projectwizard.defaultProjectId
import org.jetbrains.amper.projectwizard.describe
import org.jetbrains.amper.projectwizard.initGitRepository
import org.jetbrains.amper.projectwizard.toExtractionSchema
import org.jetbrains.amper.templates.AmperProjectTemplate
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

internal const val ProjectIdPrompt = "Project id"

private const val ProjectIdHelpHint = "See --help for --project-id requirements."

private const val ProjectIdRequirements =
    "Use at least two non-empty dot-separated segments (for example, '$DefaultProjectId'). " +
            "Each segment must start with a lowercase ASCII letter and contain only lowercase ASCII letters and digits. " +
            "Do not use Kotlin hard keywords, Java reserved words or literals as segments, or java or kotlin as the first segment"

/**
 * Shared base for the `kotlin init` and `kotlin new` commands: both generate a Kotlin project, either from one of
 * the flat [cliVisibleTemplates], or as a parameterized Compose Multiplatform application (see
 * [ComposeMultiplatformTargetPlatform]). They only differ in how the target directory is determined (see [resolveTargetDir]).
 */
internal abstract class AbstractNewProjectCommand(name: String) : AmperSubcommand(name) {

    private val projectIdOption: String? by option(
        "--project-id",
        help = "The project id. Defaults to org.example.<normalized-project-name>. Used as the Kotlin package, " +
                "Android namespace/application id, and iOS bundle id. $ProjectIdRequirements. " +
                "Ignored when --from-template is used.",
    )

    /**
     * The source of the project explicitly selected on the command line: either a set of Compose Multiplatform
     * target platforms (`--target-platform`, repeatable), or a template (`--from-template`). These are mutually
     * exclusive (Clikt reports an error if both are given), and this is `null` if neither was given.
     */
    private val explicitSource: NewProjectSource? by mutuallyExclusiveOptions(
        option(
            "--target-platform",
            help = "Adds a target platform to the generated Compose Multiplatform application. Can be repeated. " +
                    "One of: ${ComposeMultiplatformTargetPlatform.byCliValue.keys.joinToString()}.",
        ).choice(ComposeMultiplatformTargetPlatform.byCliValue)
            .transformAll { targets -> NewProjectSource.ComposeTargets(targets.toSet()) },
        option(
            "--from-template",
            help = "Generates the project from the given template instead of the parameterized Compose " +
                    "Multiplatform flow. One of: ${cliVisibleTemplates.joinToString { it.id }}.",
        ).choice(cliVisibleTemplates.associateBy { it.id })
            .convert { NewProjectSource.Template(it) },
    ).single()

    protected val overwrite: Boolean by option(
        OverwriteOptionName,
        help = "Overwrite any conflicting files or directories in the output directory instead of failing.",
    ).flag(default = false)

    private val noGit: Boolean by option(
        "--no-git",
        help = "Do not initialize a Git repository or generate Git-related files for the generated project.",
    ).flag(default = false)

    /**
     * Determines the directory to generate the project in, prompting or failing as appropriate if it depends on
     * information that wasn't supplied on the command line (e.g. the project name for `kotlin new`).
     */
    protected abstract suspend fun resolveTargetDir(): Path

    final override suspend fun run() {
        val explicitProjectId = projectIdOption
        if (explicitProjectId != null && explicitSource !is NewProjectSource.Template) {
            validatedProjectId(explicitProjectId)
        }
        val outputDir = resolveTargetDir()
        val projectName = determineProjectName(outputDir)
        val mode = resolveMode(projectName)
        val schema = mode.toExtractionSchema(generateGitFiles = !noGit)
        val overwriteConflicts = overwrite || terminal.shouldOverwriteConflictingPaths(
            relativePaths = schema.relativePaths,
            outputDir = outputDir,
        )

        terminal.withIndeterminateProgress(
            "Generating ${mode.describe(highlight = { terminal.theme.info(it) })} in $outputDir…"
        ) {
            if (overwriteConflicts) {
                clearConflictingPaths(schema.relativePaths, outputDir)
            } else {
                checkTemplateFilesConflicts(schema.relativePaths, outputDir)
            }
            outputDir.createDirectories()
            schema.extractTo(outputDir)
            generateWrapperScripts(outputDir, logger)
        }

        if (mode is NewProjectMode.ComposeMultiplatformApp && !noGit) {
            initGitRepositoryAndReport(outputDir)
        }

        printSuccessfulCommandConclusion("Project successfully generated")

        terminal.println()
        terminal.printProjectGeneratedNextSteps(outputDir, workingDir = Path(System.getProperty("user.dir")))
    }

    /**
     * Resolves which [NewProjectMode] to use, based on the CLI flags, prompting interactively for anything relevant
     * that wasn't supplied, or failing with a user-readable error if the terminal is not interactive.
     */
    private fun resolveMode(projectName: String): NewProjectMode = when (val source = explicitSource) {
        is NewProjectSource.Template -> fromTemplate(source.template, projectName)
        is NewProjectSource.ComposeTargets -> composeMultiplatformApp(source.targets, projectName)
        null -> {
            if (!terminal.terminalInfo.interactive) {
                userReadableError(
                    "Please specify at least one --target-platform " +
                            "(${ComposeMultiplatformTargetPlatform.byCliValue.keys.joinToString()}), " +
                            "or use --from-template to pick a template instead " +
                            "(one of: ${cliVisibleTemplates.joinToString { it.id }})."
                )
            }
            when (val choice = promptForFirstScreen()) {
                FirstScreenChoice.ComposeApp -> composeMultiplatformApp(
                    targets = terminal.promptForComposeMultiplatformTargets(),
                    projectName = projectName,
                )
                is FirstScreenChoice.FromTemplate -> fromTemplate(choice.template, projectName)
            }
        }
    }

    private fun fromTemplate(template: AmperProjectTemplate, projectName: String): NewProjectMode.FromTemplate {
        if (projectIdOption != null) warnIgnoredTemplateOption("--project-id")
        return NewProjectMode.FromTemplate(template, projectName)
    }

    private fun warnIgnoredTemplateOption(option: String) {
        terminal.warning("$option is ignored when generating from a template.")
    }

    private suspend fun initGitRepositoryAndReport(outputDir: Path) {
        when (val result = initGitRepository(outputDir)) {
            GitInitResult.Initialized -> terminal.println("Initialized a Git repository in $outputDir")
            GitInitResult.SkippedInsideExistingWorkTree -> terminal.println(
                "Skipped Git repository initialization because $outputDir is already inside a Git work tree."
            )
            is GitInitResult.Failed -> terminal.warning(
                "Could not initialize a Git repository in $outputDir: ${result.reason}\n" +
                        "The .gitignore and .gitattributes files were generated, " +
                        "you can run `git init` manually later."
            )
        }
    }

    private fun composeMultiplatformApp(
        targets: Set<ComposeMultiplatformTargetPlatform>,
        projectName: String,
    ): NewProjectMode.ComposeMultiplatformApp = NewProjectMode.ComposeMultiplatformApp(
        targets = targets,
        projectId = resolveProjectId(projectName),
        projectName = projectName,
    )

    private fun resolveProjectId(projectName: String): String {
        val explicitProjectId = projectIdOption
        if (explicitProjectId != null) return explicitProjectId
        val defaultProjectId = defaultProjectId(projectName)
        return if (terminal.terminalInfo.interactive) {
            terminal.promptForProjectId(defaultProjectId)
        } else {
            validatedProjectId(defaultProjectId)
        }
    }

    private fun validatedProjectId(projectId: String): String {
        try {
            checkValidProjectId(projectId)
        } catch (e: InvalidProjectIdException) {
            userReadableError("--project-id: ${e.message} $ProjectIdHelpHint")
        }
        return projectId
    }

    private sealed interface FirstScreenChoice {
        val label: String
        val description: String

        data object ComposeApp : FirstScreenChoice {
            override val label = "Compose Multiplatform application"
            override val description =
                "Compose Multiplatform clients for Android, iOS, desktop, and web, with an optional Ktor server"
        }

        data class FromTemplate(val template: AmperProjectTemplate) : FirstScreenChoice {
            override val label get() = template.name
            override val description get() = template.description
        }
    }

    private fun promptForFirstScreen(): FirstScreenChoice {
        val choices = listOf(FirstScreenChoice.ComposeApp) + cliVisibleTemplates.map { FirstScreenChoice.FromTemplate(it) }
        return terminal.interactiveSelectList(
            title = "What do you want to create?",
            items = choices,
            nameSelector = { terminal.theme.info(it.label) },
            descriptionSelector = { it.description.prependIndent("  ") },
        ) ?: throw PrintMessage("No selection made, project generation aborted")
    }
}

/**
 * The source of the project explicitly selected on the command line (see the `explicitSource` option group).
 */
private sealed interface NewProjectSource {
    data class ComposeTargets(val targets: Set<ComposeMultiplatformTargetPlatform>) : NewProjectSource
    data class Template(val template: AmperProjectTemplate) : NewProjectSource
}

internal fun Terminal.promptForComposeMultiplatformTargets(): Set<ComposeMultiplatformTargetPlatform> {
    var preselected = DefaultInteractiveComposeMultiplatformTargets
    while (true) {
        val selected = interactiveMultiSelectList(
            title = "Please select at least one target platform using ${theme.info("[Space]")}, " +
                    "and confirm with ${theme.info("[Enter]")}:",
            items = ComposeMultiplatformTargetPlatform.entries,
            nameSelector = { it.displayName },
            preselected = preselected,
            filterable = true,
        ) ?: throw PrintMessage("Command aborted.")
        if (selected.isNotEmpty()) return selected.toSet()
        // Nothing was selected, so the retry starts from an empty list rather than from the defaults again.
        preselected = []
    }
}

internal fun Terminal.promptForProjectId(defaultProjectId: String): String {
    println(
        "The project id is used as the Kotlin package, Android namespace and application id, and iOS bundle id, " +
                "and is expensive to change later."
    )
    while (true) {
        val input = prompt(prompt = ProjectIdPrompt, default = defaultProjectId)
            ?: throw PrintMessage("Command aborted.")
        try {
            checkValidProjectId(input)
            return input
        } catch (e: InvalidProjectIdException) {
            warning("${e.message} $ProjectIdHelpHint Please try again.")
        }
    }
}
