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

    private val CommandGroup = group(
        id = "ktc.command",
        version = 2,
        type = EventLogGroup.Type.COUNTER,
        description = "Command execution events",
    )

    val CommandInvoked = CommandGroup.registerEvent(
        eventId = "ktc.command.invoked",
        creator = { CommandInvokedFields() },
        description = "A CLI command was invoked",
    )

    // TODO maybe create something more flexible to handle:
    //  1) multiple top-level commands in the same call (currently not enabled, so it's OK to stay simple)
    //  2) more command nesting levels
    class CommandInvokedFields : FieldListDescription() {
        var command by field(EventFields.String(
            name = "command",
            description = "The name of the invoked command, or null if the 'kotlin' CLI was invoked without a " +
                    "command. Required if 'subcommand' is not empty.",
            // TODO find a better way to keep this in sync with the real command list
            allowedValues = [
                "build",
                "check",
                "clean",
                "clean-shared-caches",
                "compose-hot-reload-mcp-server",
                "data-collection-consent",
                "do",
                "generate-completion",
                "ide-integration",
                "init",
                "new",
                "package",
                "publish",
                "run",
                "show",
                "task",
                "test",
                "tool",
                "update",
            ],
        ))
        var subcommands by field(EventFields.StringList(
            name = "subcommand",
            description = "The name of the invoked subcommands (like 'jdk' for 'kotlin tool jdk'), or an empty list " +
                    "if no subcommands were passed (like 'kotlin build'). This is a list because some commands may " +
                    "allow multiple subcommands in the same command line.",
            // TODO find a better way to keep this in sync with the real command list
            allowedValues = [
                // show subcommands
                "checks",
                "commands",
                "dependencies",
                "modules",
                "settings",
                "tasks",
                // tool subcommands
                "jaeger",
                "jdk",
                "keystore",
                "klib",
                "xcode-integration",
                "convert-project",
                // IDE integration subcommands
                "commonize-native-distribution",
                "generate-klibs",
                "manage-xcode",
                "prepare-compose-resources",
                // data-collection-consent subcommands
                "give",
                "revoke",
                "show",
            ],
        ))
        var callerType by field(EventFields.Enum<FusCommandCallerType>(
            name = "caller_type",
            description = "The source of the CLI call (IDE, AI Agent, regular user in the terminal, etc.)",
            transform = { it.fusId },
        ))
    }

    private val EnvironmentGroup = group(
        id = "ktc.environment",
        version = 2,
        type = EventLogGroup.Type.STATE,
        description = "Environment state events",
    )

    val EnvironmentState = EnvironmentGroup.registerEvent(
        eventId = "ktc.environment.state",
        creator = { EnvironmentStateFields() },
        description = "Environment state",
    )

    class EnvironmentStateFields : FieldListDescription() {

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
    }

    private val ProjectCountersGroup = group(
        id = "ktc.project",
        version = 2,
        type = EventLogGroup.Type.COUNTER,
        description = "Project-related events of type COUNTER",
    )

    val ProjectCreated = ProjectCountersGroup.registerEvent(
        eventId = "ktc.project.created",
        creator = { ProjectCreatedFields() },
        description = "A new project was generated",
    )

    class ProjectCreatedFields : FieldListDescription() {

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
