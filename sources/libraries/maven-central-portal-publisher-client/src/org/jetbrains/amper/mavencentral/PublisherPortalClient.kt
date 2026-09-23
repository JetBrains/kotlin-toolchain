/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.mavencentral

import io.ktor.client.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.time.Duration

interface PublisherPortalClient {

    /**
     * Uploads the given [zipBundle] to the Maven Central Portal for validation and publication.
     *
     * An optional [name] may be provided for the deployment. If left unspecified, a default name will be used.
     * At the moment, the Publisher Portal uses the bundle's filename by default in this case.
     *
     * @return the unique identifier of the deployment created for the uploaded bundle. The state of the deployment can
     * then be queried using [getDeploymentStatus].
     */
    suspend fun uploadBundle(
        zipBundle: Path,
        name: String? = null,
        publishingType: PublishingType,
    ): DeploymentId

    /**
     * Returns the current status of the deployment with the given [deploymentId].
     */
    suspend fun getDeploymentStatus(deploymentId: DeploymentId): DeploymentStatus

    companion object {
        // Spec: https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle
        const val MAVEN_CENTRAL_API_URL_BASE_DEFAULT = "https://central.sonatype.com/api/v1"

        operator fun invoke(
            httpClient: HttpClient,
            userToken: UserToken,
            apiUrlBase: String = MAVEN_CENTRAL_API_URL_BASE_DEFAULT,
        ): PublisherPortalClient =
            KtorPublisherPortalClient(
                httpClient = httpClient,
                token = userToken,
                apiUrlBase = apiUrlBase,
            )
    }
}

/**
 * Polls the status of the given [deployment][deploymentId] with the specified [period], and returns distinct values.
 *
 * The returned flow stops when a final state is reached (either [PUBLISHED][DeploymentState.PUBLISHED] or
 * [FAILED][DeploymentState.FAILED]), or when the [VALIDATED][DeploymentState.VALIDATED] state is reached (if
 * [stopAfterValidated] is set to `true`).
 *
 * **Note**: If the upload was done with [PublishingType.UserManaged], the deployment will not automatically be
 * published after validation, and thus will stay in the [VALIDATED][DeploymentState.VALIDATED] state until the user
 * manually drops or publishes the deployment – which could be never. In this case, it is advised to set
 * [stopAfterValidated] to `true`.
 */
// TODO maybe use an exponential delay with cap, or fill in intermediate states
fun PublisherPortalClient.pollStatus(
    deploymentId: DeploymentId,
    period: Duration,
    stopAfterValidated: Boolean,
): Flow<DeploymentStatus> = flow {
    while (true) {
        emit(getDeploymentStatus(deploymentId))
        delay(period)
    }
}
    .distinctUntilChangedBy { it.deploymentState }
    .completeAfterFirst {
        when (it.deploymentState) {
            DeploymentState.PENDING,
            DeploymentState.VALIDATING,
            DeploymentState.PUBLISHING -> false
            DeploymentState.VALIDATED -> stopAfterValidated
            DeploymentState.PUBLISHED,
            DeploymentState.FAILED -> true
        }
    }

// Unfortunately this doesn't exist yet: https://github.com/Kotlin/kotlinx.coroutines/issues/3299
private fun <T> Flow<T>.completeAfterFirst(predicate: (T) -> Boolean): Flow<T> =
    transformWhile { emit(it); !predicate(it) }

/**
 * Credentials for accessing the Maven Central Portal API, usually referred to as a "user token".
 *
 * See [how to generate a token](https://central.sonatype.org/publish/generate-portal-token/).
 * You need a Central Portal account for this.
 * To create an account, see [the documentation](https://central.sonatype.org/register/central-portal/#create-an-account).
 */
@JvmInline
value class UserToken(
    /**
     * The base64-encoded representation of the token, which is how it's used in all API calls.
     */
    internal val base64: String,
) {
    companion object {
        /**
         * Creates a [UserToken] from its [username] and [password] components.
         *
         * See [how to generate a token](https://central.sonatype.org/publish/generate-portal-token/).
         * You need a Central Portal account for this.
         * To create an account, see [the documentation](https://central.sonatype.org/register/central-portal/#create-an-account).
         */
        fun from(username: String, password: String): UserToken =
            UserToken(Base64.UrlSafe.encode("$username:$password".toByteArray()))
    }
}

@Serializable
@JvmInline
value class DeploymentId(val uuidValue: String) {

    override fun toString(): String = uuidValue

}

/**
 * Determines whether the publication to Maven Central is fully automatic or should pause for manual checks.
 *
 * The specification about this can be read
 * [in the Sonatype docs](https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle).
 */
enum class PublishingType(internal val apiValue: String) {

    /**
     * A deployment will go through validation and, if it passes, automatically proceed to publish to Maven Central.
     */
    Automatic("AUTOMATIC"),

    /**
     * A deployment will go through validation and require the user to manually publish it via the Portal UI.
     */
    UserManaged("USER_MANAGED"),
}

/**
 * Information about a deployment in the Maven Central Portal, available after uploading a bundle.
 */
@Serializable
data class DeploymentStatus(
    val deploymentId: DeploymentId,
    val deploymentName: String,
    val deploymentState: DeploymentState,
    val purls: List<String> = emptyList(),
    val errors: Map<String, List<String>> = emptyMap(),
)

/**
 * The state of a deployment in the Maven Central Portal, available after uploading a bundle.
 */
@Serializable
enum class DeploymentState {
    /**
     * A deployment is uploaded and waiting for processing by the validation service.
     */
    PENDING,

    /**
     * A deployment is being processed by the validation service.
     */
    VALIDATING,

    /**
     * A deployment has passed validation and is waiting on a user to manually publish via the Central Portal UI.
     */
    VALIDATED,

    /**
     * A deployment has been either automatically or manually published and is being uploaded to Maven Central.
     */
    PUBLISHING,

    /**
     * A deployment has successfully been uploaded to Maven Central.
     */
    PUBLISHED,

    /**
     * A deployment has encountered an error (additional context will be present in an errors field).
     */
    FAILED,
}
