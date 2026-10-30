/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

import com.jetbrains.fus.reporting.model.lion4.FusRecorder
import com.jetbrains.fus.reporting.schema.EventFields
import com.jetbrains.fus.reporting.schema.EventLogGroup
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.RecorderDefinition
import org.jetbrains.amper.agentdetection.Agent
import org.jetbrains.amper.cidetection.CISystem
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import kotlin.jvm.java

internal const val MACHINE_ID_KEY = "machine_id"
internal const val PROJECT_ID_KEY = "project_id"

internal val KtcFusRecorder = FusRecorder(id = "KTC", version = 1)

/**
 * Provides the data used as input for the schema generation.
 */
object FusSchemaDescription {

    val groups = FusEvents.allGroups

    val recorderDefinitions = [
        RecorderDefinition(KtcFusRecorder).also {
            // `machine_id` is required, but for anonymous events it is a fixed single value for all users
            it.registerId(field = EventFields.AnonymizedId(MACHINE_ID_KEY), required = true)
            it.registerId(field = EventFields.AnonymizedId(PROJECT_ID_KEY), required = false)
        }
    ]
}

object FusEvents {

    internal val allGroups = mutableListOf<EventLogGroup>()

    private fun group(
        id: String,
        version: Int,
        type: EventLogGroup.Type,
        description: String,
    ): EventLogGroup {
        val group = EventLogGroup(
            id = id,
            version = version,
            type = type,
            description = description,
        )
        allGroups.add(group)
        return group
    }

    /**
     * Fully anonymous events for aggregated statistics that don't require user consent.
     *
     * **IMPORTANT:** events of this group are sent regardless of the user's consent, so they must not contain any
     * identifier (no machine ID, project ID, session ID, etc.), not even synthetic random ones. It must also not be
     * possible to connect these events with any other event. They must only be used for aggregated counting purposes.
     */
    internal val AnonymousGroup = group(
        id = "ktc.anonymous",
        version = 1,
        type = EventLogGroup.Type.STATE,
        description = "Fully anonymous events for aggregated statistics that don't require user consent",
    )

    val ConsentState = AnonymousGroup.registerEvent(
        eventId = "ktc.anonymous.consent.state",
        creator = { ConsentStateFields() },
        description = "The current state of the user's consent for statistical analysis only. This event is " +
                "reported at most once per day and contains no identifier.",
    )

    class ConsentStateFields : FieldListDescription() {
        var consentState by field(EventFields.Enum<FusConsentState>(
            name = "consent_state",
            description = "Whether the user accepted or rejected the collection of usage statistics, or was not " +
                    "asked yet (for example, because the terminal was not interactive).",
            transform = { it.fusId },
        ))

        var currentKtcVersion by field(EventFields.String(
            name = "ktc_version",
            description = "The currently running version of the Kotlin Toolchain. The dev build number is trimmed.",
            allowedValues = KnownKtcVersions,
        ))

        var osFamily by field(EventFields.Enum(
            name = "os_family",
            enumClass = AnonymousOsFamily::class.java,
            description = "The operating system family of the current machine",
            transform = { it.fusId },
        ))

        var ide by field(EventFields.Enum<FusIdeCode>(
            name = "ide",
            description = "The IDE running the current CLI command, or null if no IDE was detected",
            transform = { it.fusId },
        ))

        var callerType by field(EventFields.Enum<FusCommandCallerType>(
            name = "caller_type",
            description = "The source of the CLI call (IDE, AI Agent, regular user in the terminal, etc.)",
            transform = { it.fusId },
        ))

        var interactive by field(EventFields.Boolean(
            name = "interactive",
            description = "Whether the command was run in an interactive terminal.",
        ))
    }

    private val CommandGroup = group(
        id = "ktc.command",
        version = 10,
        type = EventLogGroup.Type.COUNTER,
        description = "Command execution events",
    )

    val CommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked",
        creator = { CommandInvokedFields() },
        description = "A CLI command was invoked. This event also contains contextual information like OS/arch, " +
                "whether the command was run by an AI agent, CI, or IDE, etc.",
    )

    // TODO maybe create something more flexible to handle:
    //  1) multiple top-level commands in the same call (currently not enabled, so it's OK to stay simple)
    //  2) more command nesting levels
    class CommandInvokedFields : FieldListDescription() {
        var command by field(EventFields.String(
            name = "command",
            description = "The name of the invoked command, or null if the 'kotlin' CLI was invoked without a " +
                    "command. Required if 'subcommands' is not empty.",
            allowedValues = KnownCommands,
        ))

        var subcommands by field(EventFields.StringList(
            name = "subcommand",
            description = "The name of the invoked subcommands (like 'jdk' for 'kotlin tool jdk'), or an empty list " +
                    "if no subcommands were passed (like 'kotlin build'). This is a list because some commands may " +
                    "allow multiple subcommands in the same command line.",
            allowedValues = KnownSubcommands,
        ))

        var osFamily by field(EventFields.Enum(
            name = "os_family",
            enumClass = OsFamily::class.java,
            description = "The operating system family of the current machine",
            transform = {
                when (it) {
                    OsFamily.MacOs -> "macos"
                    OsFamily.Windows -> "windows"
                    OsFamily.Linux -> "linux"
                    OsFamily.FreeBSD -> "freebsd"
                    OsFamily.Solaris -> "solaris"
                }
            },
        ))

        var cpuArch by field(EventFields.Enum(
            name = "cpu_arch",
            enumClass = Arch::class.java,
            description = "The CPU architecture of the current machine",
            transform = {
                when (it) {
                    Arch.X64 -> "x86_64"
                    Arch.Arm64 -> "arm64"
                }
            },
        ))

        var ciSystem by field(EventFields.Enum(
            name = "ci_system",
            enumClass = CISystem::class.java,
            description = "The CI system the current call is running on, or null if not running on CI",
            transform = {
                when (it) {
                    CISystem.AppVeyor -> "appveyor"
                    CISystem.AwsCodeBuild -> "codebuild"
                    CISystem.AzurePipelines -> "azure_pipelines"
                    CISystem.BitBucket -> "bitbucket"
                    CISystem.BuildKite -> "buildkite"
                    CISystem.CircleCI -> "circle_ci"
                    CISystem.GitHub -> "github"
                    CISystem.GitLab -> "gitlab"
                    CISystem.Jenkins -> "jenkins"
                    CISystem.TeamCity -> "teamcity"
                    CISystem.TravisCI -> "travis_ci"
                    CISystem.Other -> "other"
                }
            }
        ))

        var aiAgent by field(EventFields.Enum(
            name = "ai_agent",
            enumClass = Agent::class.java,
            description = "The AI agent running the current CLI command, or null if no agent was detected",
            transform = { it.fusId },
        ))

        var ide by field(EventFields.Enum<FusIdeCode>(
            name = "ide",
            description = "The IDE running the current CLI command, or null if no IDE was detected",
            transform = { it.fusId },
        ))

        var callerType by field(EventFields.Enum<FusCommandCallerType>(
            name = "caller_type",
            description = "The source of the CLI call (IDE, AI Agent, regular user in the terminal, etc.)",
            transform = { it.fusId },
        ))

        var interactive by field(EventFields.Boolean(
            name = "interactive",
            description = "Whether the command was run in an interactive terminal.",
        ))

        // We're supposed to have the full version in the basic event data, but we add this so we have a format that
        // is aligned with oldestInstalledKtcVersion for easier comparison.
        var currentKtcVersion by field(EventFields.String(
            name = "ktc_version",
            description = "The currently running version of the Kotlin Toolchain. The dev build number is trimmed.",
            allowedValues = KnownKtcVersions,
        ))

        var oldestInstalledKtcVersion by field(EventFields.String(
            name = "oldest_installed_ktc_version",
            description = "The oldest version of the Kotlin Toolchain installed in the user's cache. " +
                    "The dev build number is trimmed.",
            allowedValues = KnownKtcVersions,
        ))
    }

    val RunCommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked.run",
        creator = { RunCommandInvokedFields() },
        description = "The `kotlin run` command was invoked",
    )

    class RunCommandInvokedFields : FieldListDescription() {

        var productType by field(EventFields.Enum<FusKtcProductType>(
            name = "product_type",
            description = "The product type of the module that was run.",
            transform = { it.fusId },
        ))

        var platform by field(EventFields.Enum<FusKotlinLeafPlatform>(
            name = "platform",
            description = "The platform that the app was run on.",
            transform = { it.fusId },
        ))

        var platformSource by field(EventFields.Enum<InteractiveCliOptionSource>(
            name = "platform_source",
            description = "Whether the platform to run was specified explicitly by the user on the command line, " +
                    "prompted interactively, or inferred because there was no ambiguity.",
            transform = { it.fusId },
        ))

        var moduleSource by field(EventFields.Enum<InteractiveCliOptionSource>(
            name = "module_source",
            description = "Whether the module to run was specified explicitly by the user on the command line, " +
                    "prompted interactively, or inferred because there was no ambiguity.",
            transform = { it.fusId },
        ))

        var composeHotReload by field(EventFields.Enum<ComposeHotReloadCliOptionSource>(
            name = "compose_hot_reload",
            description = "Whether the app was run with Compose Hot Reload and how.",
            transform = { it.fusId },
        ))
    }

    val BuildCommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked.build",
        creator = { BuildCommandInvokedFields() },
        description = "The `kotlin build` command was invoked",
    )

    class BuildCommandInvokedFields : FieldListDescription() {

        var moduleCount by field(EventFields.Enum<FusModuleCountBucket>(
            name = "module_count",
            description = "The number of modules built by this command.",
            transform = { it.fusId },
        ))

        var modulesFiltered by field(EventFields.Boolean(
            name = "modules_filtered",
            description = "Whether the build was restricted to specific modules on the command line.",
        ))

        var builtPlatforms by field(enumSetField<FusKotlinLeafPlatform>(
            name = "platforms",
            description = "The platforms that this command builds for.",
        ))

        var requestedPlatforms by field(enumSetField<FusKotlinLeafPlatform>(
            name = "requested_platforms",
            description = "The platforms requested on the command line, or null if none was requested " +
                    "(in which case all the platforms of the project are built). " +
                    "See built_platforms for the list of platforms that was effectively built.",
        ))

        var requestedBuildTypes by field(enumSetField<FusBuildType>(
            name = "requested_build_types",
            description = "The build variants requested on the command line, or null if none was " +
                    "requested (in which case only the debug variant is built).",
        ))
    }

    val TestCommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked.test",
        creator = { TestCommandInvokedFields() },
        description = "The `kotlin test` command was invoked",
    )

    class TestCommandInvokedFields : FieldListDescription() {

        var moduleCount by field(EventFields.Enum<FusModuleCountBucket>(
            name = "module_count",
            description = "The number of modules tested by this command.",
            transform = { it.fusId },
        ))

        var modulesFiltered by field(EventFields.Boolean(
            name = "modules_filtered",
            description = "Whether the tests were restricted to specific modules on the command line (by including " +
                    "or excluding modules). The module names themselves are not reported.",
        ))

        var testedPlatforms by field(enumSetField<FusKotlinLeafPlatform>(
            name = "platforms",
            description = "The platforms tested by this command.",
        ))

        var requestedPlatforms by field(enumSetField<FusKotlinLeafPlatform>(
            name = "requested_platforms",
            description = "The platforms requested on the command line, or an empty list if none was requested " +
                    "(in which case tests are run for all the platforms of the project).",
        ))

        var requestedBuildType by field(EventFields.Enum<FusBuildType>(
            name = "requested_build_type",
            description = "The build variant requested on the command line, or null if none was requested " +
                    "(in which case the debug variant is tested).",
            transform = { it.fusId },
        ))

        var usedTestFilterKinds by field(enumSetField<FusTestFilterKind>(
            name = "used_test_filter_kinds",
            description = "The kinds of test filters that were used, or an empty list if no filter was used. " +
                    "The filter values themselves are not reported.",
        ))
    }

    val PublishCommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked.publish",
        creator = { PublishCommandInvokedFields() },
        description = "The `kotlin publish` command was invoked",
    )

    class PublishCommandInvokedFields : FieldListDescription() {

        var type by field(EventFields.Enum<FusPublicationType>(
            name = "type",
            description = "The kind of publication that was requested. " +
                    "This is 'ios' or 'maven', based on the special 'testflight' repository ID.",
            transform = { it.fusId },
        ))

        var modulesFiltered by field(EventFields.Boolean(
            name = "modules_filtered",
            description = "Whether the publication was restricted to specific modules on the command line. " +
                    "The module names themselves are not reported.",
        ))

        var transitive by field(EventFields.Enum<FusTransitiveOption>(
            name = "transitive",
            description = "Whether the `--transitive`/`--non-transitive` option was used, and with which value. " +
                    "'auto' means that neither option was passed on the command line.",
            transform = { it.fusId },
        ))
    }

    val CommandResult = CommandGroup.registerEvent(
        eventId = "ktc.command.result",
        creator = { CommandResultFields() },
        description = "A CLI command completed. This event is reported for any command, and complements the " +
                "corresponding 'ktc.command.invoked*' events with the outcome of the execution.",
    )

    class CommandResultFields : FieldListDescription() {

        var command by field(EventFields.String(
            name = "command",
            description = "The name of the command that completed, or null if the 'kotlin' CLI was invoked without " +
                    "a command. Required if 'subcommands' is not empty.",
            allowedValues = KnownCommands,
        ))

        var subcommands by field(EventFields.StringList(
            name = "subcommand",
            description = "The name of the subcommands that completed (like 'jdk' for 'kotlin tool jdk'), or an " +
                    "empty list if no subcommands were passed (like 'kotlin build'). This is a list because some " +
                    "commands may allow multiple subcommands in the same command line.",
            allowedValues = KnownSubcommands,
        ))

        var result by field(EventFields.Enum<FusCommandResult>(
            name = "result",
            description = "The outcome of the command execution.",
            transform = { it.fusId },
        ))
    }

    private val ProjectCountersGroup = group(
        id = "ktc.project",
        version = 4,
        type = EventLogGroup.Type.COUNTER,
        description = "Project-related events of type COUNTER",
    )

    val ProjectCreated = ProjectCountersGroup.registerEvent(
        eventId = "ktc.project.created",
        creator = { ProjectCreatedFields() },
        description = "A new project was generated",
    )

    class ProjectCreatedFields : FieldListDescription() {

        var command by field(EventFields.Enum<NewProjectCommand>(
            name = "command",
            description = "The command that was used to generate the project.",
            transform = { it.fusId }
        ))

        var templateId by field(EventFields.String(
            name = "template_id",
            description = "The id of the project template that the new project was generated from. Projects " +
                    "generated by the parameterized Kotlin Multiplatform wizard report 'kotlin-multiplatform-app'.",
            // TODO find a better way to keep this in sync with the real template list
            allowedValues = [
                "compose-android",
                "compose-desktop",
                "jvm-cli",
                "kotlin-multiplatform-app",
                "kotlin-multiplatform-library",
                "ktor-server",
                "spring-boot-kotlin",
            ],
        ))

        var targetPlatforms by field(enumSetField<FusNewProjectTargetPlatform>(
            name = "target_platforms",
            description = "The target platforms selected in the Kotlin Multiplatform wizard, or null if the " +
                    "project was generated from a template without platform selection.",
        ))

        var configuredAiAgents by field(enumSetField<Agent>(
            name = "configured_ai_agents",
            description = "The AI agents that the generated project was configured for, or an empty list if no " +
                    "agent configuration was generated.",
            transform = { it.fusId },
        ))

        var aiEnabled by field(EventFields.Boolean(
            name = "ai_enabled",
            description = "Whether AI agent configuration was enabled. " +
                    "The CLI default is true, and requires an explicit opt-out using --no-ai.",
        ))

        var gitEnabled by field(EventFields.Boolean(
            name = "git_enabled",
            description = "Whether Git initialization and Git files was enabled. " +
                    "The CLI default is true, and requires an explicit opt-out using --no-git.",
        ))
    }
}

/**
 * Returns the ID of this AI [Agent] as defined in the FUS schema.
 */
private val Agent.fusId: String
    get() = when (this) {
        Agent.Antigravity -> "antigravity"
        Agent.AugmentCli -> "augment-cli"
        Agent.ClaudeCode -> "claude-code"
        Agent.Cline -> "cline"
        Agent.Codex -> "codex"
        Agent.Cursor -> "cursor"
        Agent.CursorCli -> "cursor-cli"
        Agent.Devin -> "devin"
        Agent.GeminiCli -> "gemini-cli"
        Agent.GitHubCopilot -> "github-copilot"
        Agent.Goose -> "goose"
        Agent.Grok -> "grok"
        Agent.Junie -> "junie"
        Agent.Kimi -> "kimi"
        Agent.Kiro -> "kiro"
        Agent.OpenClaw -> "openclaw"
        Agent.OpenCode -> "opencode"
        Agent.Pi -> "pi"
        Agent.Replit -> "replit"
        Agent.Other -> "other"
    }
