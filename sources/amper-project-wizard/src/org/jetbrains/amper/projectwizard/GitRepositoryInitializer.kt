/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.IOException
import java.nio.file.Path

sealed interface GitInitResult {
    data object Initialized : GitInitResult

    data object SkippedInsideExistingWorkTree : GitInitResult

    data class Failed(val reason: String) : GitInitResult
}

private const val InitialBranch = "main"

/** Initializes a repository using JGit, without requiring a Git executable on the PATH. */
suspend fun initGitRepository(projectDir: Path): GitInitResult = withContext(Dispatchers.IO) {
    try {
        if (projectDir.isInsideGitWorkTree()) {
            GitInitResult.SkippedInsideExistingWorkTree
        } else {
            Git.init().setDirectory(projectDir.toFile()).setInitialBranch(InitialBranch).call().use { git ->
                git.normalizeLineEndings()
            }
            GitInitResult.Initialized
        }
    } catch (e: GitAPIException) {
        GitInitResult.Failed(e.message ?: "Could not initialize the repository")
    } catch (e: JGitInternalException) {
        GitInitResult.Failed(e.message ?: "Could not initialize the repository")
    } catch (e: IOException) {
        GitInitResult.Failed(e.message ?: "Could not initialize the repository")
    }
}

/**
 * Hack to normalize line endings in the repository to comply with the `.gitattributes` file.
 */
private fun Git.normalizeLineEndings() {
    add().addFilepattern(".").call()
    checkout().setAllPaths(true).call()

    val index = repository.lockDirCache()
    try {
        index.clear()
        index.write()
        if (!index.commit()) throw IOException("Could not clear the temporary Git index")
    } finally {
        index.unlock()
    }
}

private fun Path.isInsideGitWorkTree(): Boolean =
    FileRepositoryBuilder().findGitDir(toRealPath().toFile()).gitDir != null
