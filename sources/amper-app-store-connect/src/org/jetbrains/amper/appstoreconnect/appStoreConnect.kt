/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.appstoreconnect

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.X509CertificateHolder
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Serializable
private class EmptyAttributes

private val logger = LoggerFactory.getLogger("appStoreConnect")

private const val APP_STORE_CONNECT_AUDIENCE = "appstoreconnect-v1"
private const val MAX_PAGE_LIMIT = 200

/**
 * Apple rejects tokens that live longer than 20 minutes.
 */
private val TokenLifetime = 20.minutes

private val AppStoreConnectJson = Json {
    ignoreUnknownKeys = true
}

/**
 * An app registered in App Store Connect.
 */
data class AppStoreConnectApp(
    val id: String,
    val name: String,
    val bundleId: String,
    val sku: String?,
)

/**
 * A build uploaded to App Store Connect.
 */
data class AppStoreConnectBuild(
    val id: String,
    val version: String,
    val processingState: String,
)

/**
 * An App Store Connect build upload, before it becomes a processed build.
 */
data class AppStoreConnectBuildUpload(
    val version: String,
    val buildNumber: String,
)

/**
 * A bundle ID (App ID) registered in the developer team.
 */
data class AppStoreConnectBundleId(
    val id: String,
    val identifier: String,
    val name: String,
    /**
     * One of `IOS`, `MAC_OS`, or `UNIVERSAL`.
     */
    val platform: String,
    /**
     * The App ID prefix, usually equal to the team ID.
     */
    val seedId: String?,
)

/**
 * Lists all apps registered in App Store Connect that are visible with this key.
 */
suspend fun AppStoreConnectAuthKey.listApps(): List<AppStoreConnectApp> =
    getAllPages<AppAttributes>(
        action = "list the apps",
        path = "/v1/apps",
        queryParameters = mapOf("fields[apps]" to "name,bundleId,sku"),
    ).map {
        AppStoreConnectApp(
            id = it.id,
            name = it.attributes.name,
            bundleId = it.attributes.bundleId,
            sku = it.attributes.sku
        )
    }

/**
 * Finds the app registered in App Store Connect with the given [bundleId], or returns `null` if there is none.
 */
suspend fun AppStoreConnectAuthKey.findApp(bundleId: String): AppStoreConnectApp? =
    getAllPages<AppAttributes>(
        action = "look up the app record for the bundle ID '$bundleId'",
        path = "/v1/apps",
        queryParameters = mapOf("fields[apps]" to "name,bundleId,sku", "filter[bundleId]" to bundleId),
    ).map {
        AppStoreConnectApp(
            id = it.id,
            name = it.attributes.name,
            bundleId = it.attributes.bundleId,
            sku = it.attributes.sku
        )
    }.find { it.bundleId == bundleId }

/**
 * Lists all bundle IDs registered in the developer team of this key.
 */
suspend fun AppStoreConnectAuthKey.listBundleIds(): List<AppStoreConnectBundleId> =
    getAllPages<BundleIdAttributes>(
        action = "list the bundle IDs",
        path = "/v1/bundleIds",
        queryParameters = mapOf("fields[bundleIds]" to "name,identifier,platform,seedId"),
    ).map { it.toBundleId() }

/**
 * Registers a new bundle ID with the given [identifier] (e.g. `com.example.app`) in the developer team of this key.
 */
suspend fun AppStoreConnectAuthKey.createBundleId(
    identifier: String,
    name: String,
    platform: String = "IOS",
): AppStoreConnectBundleId {
    val requestBody = buildJsonObject {
        putJsonObject("data") {
            put("type", "bundleIds")
            putJsonObject("attributes") {
                put("identifier", identifier)
                put("name", name)
                put("platform", platform)
            }
        }
    }
    val responseText = request(
        url = "$apiUrl/v1/bundleIds",
        action = "register the bundle ID '$identifier'",
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        setBody(requestBody.toString())
    }
    return AppStoreConnectJson.decodeFromString<AscSingleResponse<BundleIdAttributes>>(responseText).data.toBundleId()
}

/**
 * Obtains the developer team ID (`DEVELOPMENT_TEAM`) that this key belongs to.
 *
 * The App Store Connect API doesn't expose the team ID directly, so it's inferred from the organizational unit (OU)
 * of the team's signing certificates, falling back to the App ID prefix (seed ID) of registered bundle IDs.
 * Returns `null` if the team has neither certificates nor bundle IDs.
 */
suspend fun AppStoreConnectAuthKey.obtainTeamId(): String? =
    teamIdFromCertificates() ?: listBundleIds().firstNotNullOfOrNull { it.seedId }

suspend fun AppStoreConnectAuthKey.buildNumbers(appId: String, version: String): List<String> {
    val prereleaseVersions = getAllPages<EmptyAttributes>(
        action = "find the iOS release version",
        path = "/v1/preReleaseVersions",
        queryParameters = mapOf(
            "filter[app]" to appId,
            "filter[version]" to version,
            "filter[platform]" to "IOS",
            "fields[preReleaseVersions]" to "version",
        ),
    )
    val builds = if (prereleaseVersions.isEmpty()) [] else getAllPages<BuildAttributes>(
        action = "read existing build numbers",
        path = "/v1/builds",
        queryParameters = mapOf(
            "filter[app]" to appId,
            "filter[preReleaseVersion]" to prereleaseVersions.joinToString(",") { it.id },
            "fields[builds]" to "version",
        ),
    ).map { it.attributes.version }
    val uploads = getAllPages<BuildUploadAttributes>(
        action = "read pending build uploads",
        path = "/v1/apps/$appId/buildUploads",
        queryParameters = mapOf(
            "filter[cfBundleShortVersionString]" to version,
            "filter[platform]" to "IOS",
            "fields[buildUploads]" to "cfBundleVersion",
        ),
    ).map { it.attributes.cfBundleVersion }
    return builds + uploads
}

/**
 * Finds the uploaded build with the given marketing [version] and [buildNumber], or returns `null` if it is not visible yet.
 */
suspend fun AppStoreConnectAuthKey.findBuild(
    appId: String,
    version: String,
    buildNumber: String,
): AppStoreConnectBuild? {
    val prereleaseVersions = getAllPages<EmptyAttributes>(
        action = "find the iOS release version",
        path = "/v1/preReleaseVersions",
        queryParameters = mapOf(
            "filter[app]" to appId,
            "filter[version]" to version,
            "filter[platform]" to "IOS",
            "fields[preReleaseVersions]" to "version",
        ),
    )
    if (prereleaseVersions.isEmpty()) return null

    val build = getAllPages<BuildProcessingAttributes>(
        action = "check the uploaded build's processing status",
        path = "/v1/builds",
        queryParameters = mapOf(
            "filter[app]" to appId,
            "filter[preReleaseVersion]" to prereleaseVersions.joinToString(",") { it.id },
            "filter[version]" to buildNumber,
            "fields[builds]" to "version,processingState",
        ),
    ).firstOrNull { it.attributes.version == buildNumber } ?: return null
    return AppStoreConnectBuild(
        id = build.id,
        version = build.attributes.version,
        processingState = build.attributes.processingState,
    )
}

/**
 * Reads the exact marketing version and build number recorded for [uploadId].
 */
suspend fun AppStoreConnectAuthKey.getBuildUpload(uploadId: String): AppStoreConnectBuildUpload {
    val url = URLBuilder("$apiUrl/v1/buildUploads/$uploadId").apply {
        parameters.append("fields[buildUploads]", "cfBundleShortVersionString,cfBundleVersion")
    }.buildString()
    val responseText = request(
        url = url,
        action = "read the App Store Connect build upload '$uploadId'",
    ) { method = HttpMethod.Get }
    val upload = AppStoreConnectJson.decodeFromString<AscSingleResponse<BuildUploadDetailsAttributes>>(responseText).data
    return AppStoreConnectBuildUpload(
        version = upload.attributes.cfBundleShortVersionString,
        buildNumber = upload.attributes.cfBundleVersion,
    )
}

@Serializable
private data class BuildAttributes(val version: String)

@Serializable
private data class BuildProcessingAttributes(
    val version: String,
    val processingState: String,
)

@Serializable
private data class BuildUploadAttributes(val cfBundleVersion: String)

@Serializable
private data class BuildUploadDetailsAttributes(
    val cfBundleShortVersionString: String,
    val cfBundleVersion: String,
)

/**
 * An App Store Connect operation failed.
 * [status] is `null` for network errors (after the HTTP client's own retries) and for local validation or credential errors.
 */
class AppStoreConnectException(
    message: String,
    cause: Throwable? = null,
    val status: HttpStatusCode? = null,
) : RuntimeException(message, cause)

private suspend fun AppStoreConnectAuthKey.teamIdFromCertificates(): String? =
    getAllPages<CertificateAttributes>(
        action = "list the certificates",
        path = "/v1/certificates",
        queryParameters = mapOf("fields[certificates]" to "certificateContent"),
    ).firstNotNullOfOrNull { certificate ->
        certificate.attributes.certificateContent?.let(::organizationalUnitOf)
    }

private fun organizationalUnitOf(base64DerCertificate: String): String? {
    val certificate = X509CertificateHolder(Base64.decode(base64DerCertificate))
    return certificate.subject.getRDNs(BCStyle.OU).firstOrNull()?.first?.value?.toString()
}

private suspend inline fun <reified A> AppStoreConnectAuthKey.getAllPages(
    action: String,
    path: String,
    queryParameters: Map<String, String>,
): List<AscResource<A>> = buildList {
    var nextUrl: String? = URLBuilder("$apiUrl$path").apply {
        for ((key, value) in queryParameters) {
            parameters.append(key, value)
        }
        parameters.append("limit", MAX_PAGE_LIMIT.toString())
    }.buildString()
    while (nextUrl != null) {
        val responseText = request(url = nextUrl, action = action) { method = HttpMethod.Get }
        val page = AppStoreConnectJson.decodeFromString<AscListResponse<A>>(responseText)
        addAll(page.data)
        nextUrl = page.links?.next
    }
}

/**
 * Performs a request to the App Store Connect API and returns the response body.
 *
 * On failure, reports a user-readable error saying that it was unable to [action], with the error details from the
 * response and an optional [errorHint] for the response status.
 */
private suspend fun AppStoreConnectAuthKey.request(
    url: String,
    action: String,
    errorHint: (HttpStatusCode) -> String? = { null },
    configure: HttpRequestBuilder.() -> Unit,
): String {
    val token = createJwt()
    return try {
        amperHttpClient.request(url) {
            configure()
            bearerAuth(token)
            // Apple's API answers `deflate` with zlib-wrapped data, while Ktor's decoder expects raw deflate.
            // Setting the header explicitly prevents the ContentEncoding plugin from advertising `deflate`.
            header(HttpHeaders.AcceptEncoding, "gzip")
        }.bodyAsText()
    } catch (e: ResponseException) {
        val status = e.response.status
        val responseText = e.response.bodyAsText()
        logger.debug("App Store Connect API request to {} failed with {}:\n{}", url, status, responseText)
        val hint = errorHint(status) ?: defaultErrorHint(status)
        throw AppStoreConnectException(
            status = status,
            message = buildString {
                append("Unable to $action in App Store Connect: ")
                append(errorDetails(responseText) ?: "the server responded with ${status.value} ${status.description}.")
                if (hint != null) append("\n").append(hint)
            },
            cause = e,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The HTTP client has already retried, so this is a persistent network error (or a timeout)
        logger.debug("App Store Connect API request to {} failed", url, e)
        throw AppStoreConnectException(
            message = "Unable to $action in App Store Connect: ${e.message ?: e::class.simpleName}\n" +
                    "Check your network connection.",
            cause = e,
        )
    }
}

/**
 * Extracts the human-readable error messages from an App Store Connect API error response,
 * or returns `null` if there are none.
 */
private fun errorDetails(responseText: String): String? {
    val errors = runCatching { AppStoreConnectJson.decodeFromString<AscErrorResponse>(responseText).errors }.getOrNull()
    return errors
        ?.mapNotNull { error -> error.detail?.takeIf { it.isNotBlank() } ?: error.title?.takeIf { it.isNotBlank() } }
        ?.distinct()
        ?.joinToString("\n")
        ?.ifEmpty { null }
}

private fun defaultErrorHint(status: HttpStatusCode): String? = when (status) {
    HttpStatusCode.Unauthorized -> "Make sure the App Store Connect API key, key ID, and issuer ID are valid " +
            "and the key has not been revoked."
    HttpStatusCode.Forbidden -> "Make sure the App Store Connect API key has the App Manager or Admin role."
    else -> if (status.value >= 500) {
        "App Store Connect is having issues; check https://developer.apple.com/system-status/ and try again later."
    } else null
}

/**
 * Creates a signed JWT for the App Store Connect API, as described in
 * [the docs](https://developer.apple.com/documentation/appstoreconnectapi/generating-tokens-for-api-requests).
 */
private fun AppStoreConnectAuthKey.createJwt(now: Instant = Clock.System.now()): String {
    val header = buildJsonObject {
        put("alg", "ES256")
        put("kid", keyId)
        put("typ", "JWT")
    }
    val payload = buildJsonObject {
        put("iss", issuerId)
        put("iat", now.epochSeconds)
        put("exp", (now + TokenLifetime).epochSeconds)
        put("aud", APP_STORE_CONNECT_AUDIENCE)
    }
    val signingInput =
        "${header.toString().encodeToByteArray().base64Url()}.${payload.toString().encodeToByteArray().base64Url()}"
    // JWT requires the raw R||S signature format (IEEE P1363) rather than the DER encoding produced by default
    val signature = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
        initSign(loadPrivateKey())
        update(signingInput.encodeToByteArray())
    }.sign()
    return "$signingInput.${signature.base64Url()}"
}

private fun AppStoreConnectAuthKey.loadPrivateKey(): PrivateKey {
    try {
        val pkcs8Base64 = keyPath.readText()
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("") { it.trim() }
        val keySpec = PKCS8EncodedKeySpec(Base64.decode(pkcs8Base64))
        return KeyFactory.getInstance("EC").generatePrivate(keySpec)
    } catch (e: Exception) {
        throw AppStoreConnectException(
            "Unable to read the App Store Connect private key at '$keyPath'. Use the downloaded .p8 key.",
            cause = e
        )
    }
}

private fun ByteArray.base64Url(): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(this)

private fun AscResource<BundleIdAttributes>.toBundleId() = AppStoreConnectBundleId(
    id = id,
    identifier = attributes.identifier,
    name = attributes.name,
    platform = attributes.platform,
    seedId = attributes.seedId,
)

@Serializable
private data class AscListResponse<A>(
    val data: List<AscResource<A>>,
    val links: AscPagedLinks? = null,
)

@Serializable
private data class AscSingleResponse<A>(
    val data: AscResource<A>,
)

@Serializable
private data class AscResource<A>(
    val id: String,
    val type: String,
    val attributes: A,
)

@Serializable
private data class AscPagedLinks(
    val next: String? = null,
)

@Serializable
private data class AppAttributes(
    val name: String,
    val bundleId: String,
    val sku: String? = null,
)

@Serializable
private data class BundleIdAttributes(
    val identifier: String,
    val name: String,
    val platform: String,
    val seedId: String? = null,
)

@Serializable
private data class AscErrorResponse(
    val errors: List<AscError> = [],
)

@Serializable
private data class AscError(
    val title: String? = null,
    val detail: String? = null,
)

@Serializable
private data class CertificateAttributes(
    val certificateContent: String? = null,
)