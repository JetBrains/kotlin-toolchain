/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

import org.jetbrains.amper.frontend.schema.Repository

data class RepositoryCredentials(
    val userName: String,
    val password: String,
)

/**
 * Repository that is used for resolution.
 * Based on [Repository] schema node where [Repository.resolve] is `true`.
 *
 * [credentials] are read eagerly.
 */
data class ResolutionRepository(
    val id: String,
    val url: String,
    val credentials: RepositoryCredentials? = null,
)

/**
 * Repository that is used for publication.
 * Based on [Repository] schema node where [Repository.publish] is `true`.
 *
 * Credentials are not read until needed, use [credentialsSource] for the info.
 */
data class PublicationRepository(
    val id: String,
    val url: String,
    val credentialsSource: Repository.Credentials?,
)

val ResolutionRepository.isMavenLocal: Boolean get() = url == Repository.SpecialMavenLocalUrl

val PublicationRepository.isMavenLocal: Boolean get() = url == Repository.SpecialMavenLocalUrl
