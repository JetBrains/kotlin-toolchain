/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

import org.jetbrains.amper.frontend.schema.Repository
import kotlin.contracts.contract

/**
 * Model based on [Repository] schema.
 */
sealed interface RepositoryModel {
    val id: String
    val url: String

    data class Credentials(
        val userName: String,
        val password: String,
    )

    /**
     * Repository can be used for resolution
     */
    sealed interface Resolve : RepositoryModel {
        val credentials: Credentials?
    }

    /**
     * Repository can be used for publication
     */
    sealed interface Publish : RepositoryModel

    data class ResolveAndPublish(
        override val id: String,
        override val url: String,
        override val credentials: Credentials? = null,
    ) : Resolve, Publish

    data class ResolveOnly(
        override val id: String,
        override val url: String,
        override val credentials: Credentials? = null,
    ) : Resolve

    data class PublishOnly(
        override val id: String,
        override val url: String,
        val credentialsSource: Repository.Credentials?,
    ) : Publish
}

/**
 * Equivalent to the `this is RepositoryModel.Resolve` check.
 */
val RepositoryModel.resolve: Boolean
    get() {
        contract {
            returns(true) implies (this@resolve is RepositoryModel.Resolve)
            returns(false) implies (this@resolve is RepositoryModel.PublishOnly)
        }
        return this is RepositoryModel.Resolve
    }

/**
 * Equivalent to the `this is RepositoryModel.Publish` check.
 */
val RepositoryModel.publish: Boolean
    get() {
        contract {
            returns(true) implies (this@publish is RepositoryModel.Publish)
            returns(false) implies (this@publish is RepositoryModel.ResolveOnly)
        }
        return this is RepositoryModel.Publish
    }

val RepositoryModel.isMavenLocal: Boolean get() = url == Repository.SpecialMavenLocalUrl
