/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.agentdetection.Agent
import org.jetbrains.amper.agentdetection.isRunByAgent
import org.jetbrains.amper.cli.analytics.CliFusReporter
import org.jetbrains.amper.cli.terminal.interactiveMultiSelectList
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.cli.terminal.promptBoolean
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.fus.schema.FusNewProjectTargetPlatform
import org.jetbrains.amper.fus.schema.NewProjectCommand
import org.jetbrains.amper.projectwizard.AiAgent
import org.jetbrains.amper.projectwizard.KotlinMultiplatformTargetPlatform
import org.jetbrains.amper.projectwizard.DefaultInteractiveKotlinMultiplatformTargets
import org.jetbrains.amper.projectwizard.GitInitResult
import org.jetbrains.amper.projectwizard.InvalidProjectIdException
import org.jetbrains.amper.projectwizard.checkValidProjectId
import org.jetbrains.amper.projectwizard.defaultProjectId
import org.jetbrains.amper.projectwizard.initGitRepository
import org.jetbrains.amper.templates.AmperProjectTemplate
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeToOrNull

internal const val ProjectIdPrompt = "Project id"

private const val ComposeDesktopTemplateId = "compose-desktop"
private const val KmpLibraryTemplateId = "kotlin-multiplatform-library"
private const val ProjectIdHelpHint = "See --help for --project-id requirements."
private const val ProjectIdImpact =
    "Changing it later requires updating source packages, source layout, and platform configuration, so choose carefully."

// One hint for all agents. Evaluated through LiteLLM with Claude Code (opus 5.5, medium effort) and Codex
// (gpt-6.1-sol, medium effort): desktop was added in 47/50 and 49/50 runs respectively.
// gemini 4 argon seems to work with this hint
private const val MobileAppHint =
    "When the project includes the android or ios platform, it is normally created with the desktop target as well: " +
            "pass --target-platform=desktop in addition to the other --target-platform flags. " +
            "Desktop here is a development target, not an extra product: it runs the shared UI on the JVM with " +
            "Compose Hot Reload (the only target that supports it), so UI changes show up instantly without an " +
            "emulator or Xcode. It does not change the Android or iOS apps, and it is easy to drop later by deleting " +
            "the desktopApp module and its entry in project.yaml. " +
            "Adding it without being asked is fine and expected; just mention it to the user. " +
            "Only omit it if the user explicitly excludes it."

private const val ProjectIdRequirements =
    "Use at least two non-empty dot-separated segments (for example, '$DefaultProjectId'). " +
            "Each segment must start with a lowercase ASCII letter and contain only lowercase ASCII letters and digits. " +
            "Do not use Kotlin hard keywords, Java reserved words or literals as segments, or java or kotlin as the first segment"

/**
 * Shared base for the `kotlin init` and `kotlin new` commands: both generate a Kotlin project, either from one of
 * the flat [cliVisibleTemplates], or as a parameterized Compose Multiplatform application (see
 * [KotlinMultiplatformTargetPlatform]). They only differ in how the target directory is determined (see [resolveTargetDir]).
 */
internal sealed class AbstractNewProjectCommand(name: String) : AmperSubcommand(name) {
    private sealed interface AiConfigurationOption {
        data object NoAi : AiConfigurationOption
        data class SelectedAgents(val agents: List<AiAgent>) : AiConfigurationOption
    }

    final override fun helpEpilog(context: Context): String = buildString {
        if (isRunByAgent()) {
            append(MobileAppHint)
        }
    }

    private val projectIdOption: String? by option(
        "--project-id",
        help = "The project id. Defaults to org.example.<normalized-project-name>. Used as the Kotlin package, " +
                "Android namespace/application id, and iOS bundle id. $ProjectIdImpact $ProjectIdRequirements. " +
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
                    "One of: ${KotlinMultiplatformTargetPlatform.byCliValue.keys.joinToString()}.",
        ).choice(KotlinMultiplatformTargetPlatform.byCliValue)
            .transformAll { targets -> NewProjectSource.ComposeTargets(targets.toSet()) },
        option("--from-template")
            .help {
                "Generates the project from the given template instead of the parameterized Compose " +
                        "Multiplatform flow. One of: ${cliVisibleTemplates.joinToString { it.id }}."
            }
            .choice(cliVisibleTemplates.associateBy { it.id })
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

    private val aiConfigurationOption by mutuallyExclusiveOptions(
        option("--no-ai", help = "Do not generate files that configure AI agents.")
            .switch("--no-ai" to AiConfigurationOption.NoAi),
        option(
            "--agent",
            help = "Generate AI configuration only for this agent. Can be repeated. " +
                    "One of: ${AiAgent.byCliValue.keys.joinToString()}. " +
                    "Required in non-interactive mode unless --no-ai is given; " +
                    "in interactive mode, prompts for the agents.",
        ).choice(AiAgent.byCliValue).transformAll { agents ->
            agents.takeIf { it.isNotEmpty() }?.let(AiConfigurationOption::SelectedAgents)
        },
    ).single()

    private val noAi: Boolean get() = aiConfigurationOption == AiConfigurationOption.NoAi
    private val agentOptions: List<AiAgent>
        get() = (aiConfigurationOption as? AiConfigurationOption.SelectedAgents)?.agents.orEmpty()

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
        val agents = resolveAgents()
        val schema = mode.toExtractionSchema(
            generateGitFiles = !noGit,
            generateAiFiles = !noAi && agents.isNotEmpty(),
            agents = agents,
        )
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

        if (mode is NewProjectMode.KotlinMultiplatformApp && !noGit) {
            initGitRepositoryAndReport(outputDir)
        }

        printSuccessfulCommandConclusion("Project successfully generated")
        reportProjectCreated(outputDir, mode, agents)

        terminal.println()
        printNextSteps(mode, outputDir)

        val workingDir = Path(System.getProperty("user.dir"))
        if (!noAi && mode.configuresComposeHotReloadMcp() && isRunByAgent()) {
            printMcpReloadHintForAgent(outputDir, workingDir)
        }
    }

    private fun reportProjectCreated(outputDir: Path, mode: NewProjectMode, agents: Set<AiAgent>) {
        CliFusReporter.setProject(outputDir)
        CliFusReporter.log(FusEvents.ProjectCreated) {
            command = when (this@AbstractNewProjectCommand) {
                is InitCommand -> NewProjectCommand.Init
                is NewCommand -> NewProjectCommand.New
            }
            templateId = mode.templateId
            targetPlatforms = when (mode) {
                is NewProjectMode.KotlinMultiplatformApp -> mode.targets.map { it.toFusTargetPlatform() }
                is NewProjectMode.FromTemplate -> null
            }
            configuredAiAgents = agents.map { it.toAgent() }
            aiEnabled = !noAi
            gitEnabled = !noGit
        }
    }

    /**
     * Converts a [KotlinMultiplatformTargetPlatform] to its corresponding [FusNewProjectTargetPlatform].
     *
     * This mapping is used to bridge between the target platform representations in the CLI and the FUS
     * (Feature Usage Statistics) system for new project configurations.
     */
    private fun KotlinMultiplatformTargetPlatform.toFusTargetPlatform(): FusNewProjectTargetPlatform = when (this) {
        KotlinMultiplatformTargetPlatform.ANDROID -> FusNewProjectTargetPlatform.Android
        KotlinMultiplatformTargetPlatform.IOS -> FusNewProjectTargetPlatform.IOS
        KotlinMultiplatformTargetPlatform.DESKTOP -> FusNewProjectTargetPlatform.Desktop
        KotlinMultiplatformTargetPlatform.WEB  -> FusNewProjectTargetPlatform.Web
        KotlinMultiplatformTargetPlatform.SERVER -> FusNewProjectTargetPlatform.Server
    }

    // TODO get rid of this when we generally use the Agent enum everywhere
    private fun AiAgent.toAgent(): Agent = when (this) {
        AiAgent.CLAUDE -> Agent.ClaudeCode
        AiAgent.CODEX -> Agent.Codex
        AiAgent.ANTIGRAVITY -> Agent.Antigravity
        AiAgent.JUNIE -> Agent.Junie
        AiAgent.PI -> Agent.Pi
        AiAgent.OPENCODE -> Agent.OpenCode
    }

    /**
     * Prints a note addressed to the AI agent that runs this command, explaining that MCP servers configured by the
     * freshly generated project (such as the Compose Hot Reload) are not picked up by the current agent session, and
     * asking it to relay to the user how to make them available.
     *
     * Agents only load MCP servers when a session starts (or when the user explicitly reloads them), and they look for
     * files like `.mcp.json` in the directory they were started in. The project directory is [outputDir], while the
     * agent session was likely started in [workingDir].
     */
    private fun printMcpReloadHintForAgent(outputDir: Path, workingDir: Path) {
        val absoluteWorkingDir = workingDir.toAbsolutePath().normalize()
        val absoluteOutputDir = absoluteWorkingDir.resolve(outputDir).normalize()

        terminal.println()
        terminal.println("Note for AI agents: this project configures MCP servers (for example, \"Compose Hot Reload\").")
        terminal.println("They are not available in the current session yet, because MCP servers are only loaded when a session starts.")
        if (absoluteOutputDir == absoluteWorkingDir) {
            terminal.println("Please ask the user to reload the MCP servers (or restart the agent session), so that you can use them.")
        } else {
            val readablePath = absoluteOutputDir.relativeToOrNull(absoluteWorkingDir) ?: absoluteOutputDir
            terminal.println("Please ask the user to restart you from the project directory (cd $readablePath), so that you can use them.")
        }
    }

    /** Whether the generated project has the Compose Hot Reload MCP server configured (desktop apps only). */
    private fun NewProjectMode.configuresComposeHotReloadMcp(): Boolean = when (this) {
        is NewProjectMode.KotlinMultiplatformApp -> KotlinMultiplatformTargetPlatform.DESKTOP in targets
        is NewProjectMode.FromTemplate -> template.id == ComposeDesktopTemplateId
    }

    private fun printNextSteps(mode: NewProjectMode, outputDir: Path) {
        val cd = cdPrefix(outputDir, workingDir = Path(System.getProperty("user.dir")))
        when (mode) {
            is NewProjectMode.KotlinMultiplatformApp -> printRunHint(cd)
            is NewProjectMode.FromTemplate -> when (mode.template.id) {
                KmpLibraryTemplateId -> printBuildHint(cd)
                else -> printRunHint(cd)
            }
        }
        terminal.println("Or open the generated folder in an IDE with the Kotlin Toolchain plugin.")
    }

    private fun printRunHint(cd: String) {
        terminal.println("To run your application:")
        terminal.println("  ${terminal.theme.info("${cd}kotlin run")}")
    }

    private fun printBuildHint(cd: String) {
        terminal.println("Now you may build your project with:")
        terminal.println("  ${terminal.theme.info("${cd}kotlin build")}")
    }

    private fun resolveAgents(): Set<AiAgent> = when {
        noAi -> []
        agentOptions.isNotEmpty() -> agentOptions.toSet()
        terminal.terminalInfo.interactive -> terminal.promptForAiAgents()
        else -> userReadableError(
            "Please specify which AI agents to configure with --agent " +
                    "(one of: ${AiAgent.byCliValue.keys.joinToString()}; can be repeated), " +
                    "or pass --no-ai to skip AI configuration."
        )
    }

    /**
     * Resolves which [NewProjectMode] to use, based on the CLI flags, prompting interactively for anything relevant
     * that wasn't supplied, or failing with a user-readable error if the terminal is not interactive.
     */
    private fun resolveMode(projectName: String): NewProjectMode = when (val source = explicitSource) {
        is NewProjectSource.Template -> fromTemplate(source.template)
        is NewProjectSource.ComposeTargets -> kotlinMultiplatformApp(source.targets, projectName)
        null -> {
            if (!terminal.terminalInfo.interactive) {
                userReadableError(
                    "Please specify at least one --target-platform " +
                            "(${KotlinMultiplatformTargetPlatform.byCliValue.keys.joinToString()}), " +
                            "or use --from-template to pick a template instead " +
                            "(one of: ${cliVisibleTemplates.joinToString { it.id }})."
                )
            }
            when (val choice = promptForFirstScreen()) {
                FirstScreenChoice.ComposeApp -> kotlinMultiplatformApp(
                    targets = terminal.promptForKotlinMultiplatformTargets(),
                    projectName = projectName,
                )
                is FirstScreenChoice.FromTemplate -> fromTemplate(choice.template)
            }
        }
    }

    private fun fromTemplate(template: AmperProjectTemplate): NewProjectMode.FromTemplate {
        if (projectIdOption != null) warnIgnoredTemplateOption("--project-id")
        return NewProjectMode.FromTemplate(template)
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

    private fun kotlinMultiplatformApp(
        targets: Set<KotlinMultiplatformTargetPlatform>,
        projectName: String,
    ): NewProjectMode.KotlinMultiplatformApp = NewProjectMode.KotlinMultiplatformApp(
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
            override val label = "Kotlin Multiplatform application"
            override val description =
                "Kotlin Application for Android, iOS, web, and/or desktop, with an optional Ktor server"
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
    data class ComposeTargets(val targets: Set<KotlinMultiplatformTargetPlatform>) : NewProjectSource
    data class Template(val template: AmperProjectTemplate) : NewProjectSource
}

internal fun Terminal.promptForKotlinMultiplatformTargets(): Set<KotlinMultiplatformTargetPlatform> {
    var preselected = DefaultInteractiveKotlinMultiplatformTargets
    while (true) {
        val selected = interactiveMultiSelectList(
            title = "Please select at least one target platform using ${theme.info("[Space]")}, " +
                    "and confirm with ${theme.info("[Enter]")}:",
            items = KotlinMultiplatformTargetPlatform.entries,
            nameSelector = { it.displayName },
            preselected = preselected,
            filterable = true,
        ) ?: throw PrintMessage("Command aborted.")
        if (selected.isNotEmpty()) return selected.toSet()
        // Nothing was selected, so the retry starts from an empty list rather than from the defaults again.
        preselected = []
    }
}

internal fun Terminal.promptForAiAgents(): Set<AiAgent> {
    val install = promptBoolean("Install AI agent configuration?", default = true)
        ?: throw PrintMessage("Command aborted.")
    if (!install) return []

    var preselected = AiAgent.entries.toSet()
    while (true) {
        val selected = interactiveMultiSelectList(
            title = "Please select at least one AI agent using ${theme.info("[Space]")}, " +
                    "and confirm with ${theme.info("[Enter]")}:",
            items = AiAgent.entries,
            nameSelector = { it.displayName },
            preselected = preselected,
            filterable = true,
        ) ?: throw PrintMessage("Command aborted.")
        if (selected.isNotEmpty()) return selected.toSet()
        preselected = []
    }
}

internal fun Terminal.promptForProjectId(defaultProjectId: String): String {
    println(
        "The project id is used as the Kotlin package, Android namespace and application id, and iOS bundle id. " +
                ProjectIdImpact
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
