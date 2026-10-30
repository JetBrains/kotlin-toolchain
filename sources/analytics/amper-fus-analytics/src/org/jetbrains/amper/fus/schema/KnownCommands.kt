/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

// TODO find a better way to keep this in sync with the real command list
internal val KnownCommands = [
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
]

// TODO find a better way to keep this in sync with the real command list
internal val KnownSubcommands = [
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
]
