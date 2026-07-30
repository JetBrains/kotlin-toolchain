/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.options

import com.github.ajalt.clikt.completion.CompletionCandidates

internal val ModuleCompletionCandidates = CompletionCandidates.Custom
    .fromStdout("kotlin show modules --format=plain")

internal val CustomCommandsCompletionCandidates = CompletionCandidates.Custom
    .fromStdout("kotlin show commands --format=plain")

internal val ChecksCompletionCandidates = CompletionCandidates.Custom
    .fromStdout("kotlin show checks --format=plain")
