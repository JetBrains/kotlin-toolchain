/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication.ios

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.addAll
import kotlin.collections.all
import kotlin.collections.associate
import kotlin.collections.distinct
import kotlin.collections.emptyMap
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.getValue
import kotlin.collections.isNotEmpty
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.set
import kotlin.collections.toList

private const val ControlUploadsPath = "/__fake/uploads"
private val appBuildUploadsPath = Regex("/v1/apps/([^/]+)/buildUploads")

/**
 * Starts a fake App Store Connect API server that accepts JWTs signed with the given [key],
 * runs the [block] with it, and always stops the server afterward.
 */
internal suspend fun <T> withFakeAppStoreConnect(
    key: AscTestKey,
    configure: FakeAscState.() -> Unit = {},
    block: suspend (FakeAppStoreConnect) -> T,
): T = FakeAppStoreConnect(key, FakeAscState().apply(configure)).use { block(it) }

/**
 * A request received by the fake App Store Connect server.
 * [authError] is `null` if the request was properly authorized.
 */
internal data class AscRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: String,
    val authError: String?,
)

/**
 * A build upload registered via the control endpoint `POST /__fake/uploads`.
 */
internal data class FakeUpload(val id: String, val version: String, val buildNumber: String)

private class FakeBundleId(
    val id: String,
    val identifier: String,
    val name: String,
    val platform: String,
    val seedId: String?,
)

private class FakeApp(val id: String, val name: String, val bundleId: String, val sku: String?)

internal class AscResponse(val status: Int, val body: JsonObject) {
    companion object {
        /**
         * Closes the connection without sending a response, which looks like a network failure to the client.
         */
        val DroppedConnection = AscResponse(status = 0, body = JsonObject(emptyMap()))
    }
}

/**
 * A [FakeAscState.buildStates] item that drops the connection instead of answering.
 */
internal object DropConnection

/**
 * The configurable state of the fake App Store Connect server. It mutates while the server is serving requests.
 */
internal class FakeAscState {
    /**
     * The seed ID given to bundle IDs created via `POST /v1/bundleIds`.
     */
    var teamId: String = "A1B2C3D4E5"

    private val bundleIds = mutableListOf<FakeBundleId>()
    private val apps = mutableListOf<FakeApp>()
    private val conflictingIdentifiers = mutableSetOf<String>()
    private val buildStates = ArrayDeque<Any>(["VALID"])
    private var buildUploadLookupFails = false
    private val failedRoutes = mutableMapOf<Pair<String, String>, Int>()
    private val droppedRoutes = mutableSetOf<Pair<String, String>>()
    private val uploads = mutableListOf<FakeUpload>()

    @Synchronized
    fun bundleId(
        identifier: String,
        seedId: String? = teamId,
        name: String = "Test App",
        id: String = generateBundleIdResourceId(),
    ) {
        bundleIds.add(FakeBundleId(id, identifier, name, platform = "IOS", seedId = seedId))
    }

    @Synchronized
    fun app(bundleId: String, name: String = "Testflight App", id: String = "1234567890", sku: String? = bundleId) {
        apps.add(FakeApp(id, name, bundleId, sku))
    }

    /**
     * Makes `POST /v1/bundleIds` for the given [identifier] fail with 409 Conflict.
     */
    @Synchronized
    fun conflictOnCreate(identifier: String) {
        conflictingIdentifiers.add(identifier)
    }

    /**
     * Sets the sequence of answers to successive build lookups (`GET /v1/builds` with a known build number).
     * Each item is either a `processingState` [String] (e.g. `PROCESSING`, `VALID`, `FAILED`), an [Int] HTTP status
     * to answer with an error, or [DropConnection]. The last item is repeated forever.
     *
     * Note that the CLI's HTTP client retries server errors and network failures by itself a few times
     * before the product code sees the failure.
     */
    @Synchronized
    fun buildStates(vararg states: Any) {
        require(states.isNotEmpty()) { "At least one build state is required" }
        require(states.all { it is String || it is Int || it == DropConnection }) {
            "Build states must be Strings, Ints, or DropConnection: ${states.toList()}"
        }
        buildStates.clear()
        buildStates.addAll(states)
    }

    /**
     * Makes `GET /v1/buildUploads/{id}` fail with 404.
     */
    @Synchronized
    fun failBuildUploadLookup() {
        buildUploadLookupFails = true
    }

    /**
     * Makes every authorized request with the given [method] and exact [path] fail with the given [status].
     */
    @Synchronized
    fun failRoute(method: String, path: String, status: Int) {
        failedRoutes[method to path] = status
    }

    /**
     * Makes every authorized request with the given [method] and exact [path] drop the connection without a response.
     */
    @Synchronized
    fun dropConnections(method: String, path: String) {
        droppedRoutes.add(method to path)
    }

    @Synchronized
    internal fun uploadsSnapshot(): List<FakeUpload> = uploads.toList()

    @Synchronized
    internal fun registerUpload(version: String, buildNumber: String): FakeUpload =
        FakeUpload(id = UUID.randomUUID().toString(), version = version, buildNumber = buildNumber)
            .also { uploads.add(it) }

    /**
     * Answers an authorized `/v1` request, or returns `null` if no route matches.
     */
    @Synchronized
    internal fun answer(request: AscRequest): AscResponse? {
        if ((request.method to request.path) in droppedRoutes) return AscResponse.DroppedConnection
        failedRoutes[request.method to request.path]?.let { return errorResponse(it) }
        return when (request.method) {
            "GET" -> answerGet(request.path, request.query)
            "POST" -> if (request.path == "/v1/bundleIds") createBundleId(request.body) else null
            else -> null
        }
    }

    private fun answerGet(path: String, query: Map<String, String>): AscResponse? = when (path) {
        "/v1/bundleIds" -> ok(listBody(bundleIds.map { it.toResource() }))
        "/v1/certificates" -> ok(listBody([]))
        "/v1/apps" -> ok(listBody(appsMatching(query["filter[bundleId]"]).map { it.toResource() }))
        "/v1/preReleaseVersions" -> ok(listBody(preReleaseVersions(query["filter[version]"])))
        "/v1/builds" -> findBuilds(query)
        else -> answerGetResource(path, query)
    }

    private fun answerGetResource(path: String, query: Map<String, String>): AscResponse? {
        val uploadId = path.removePrefix("/v1/buildUploads/").takeIf { it != path && it.isNotEmpty() && '/' !in it }
        return when {
            uploadId != null -> getBuildUpload(uploadId)
            appBuildUploadsPath.matches(path) -> ok(listBody(uploadsMatching(query).map { it.toBuildUploadResource() }))
            else -> null
        }
    }

    private fun createBundleId(body: String): AscResponse {
        val attributes = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
            .getValue("attributes").jsonObject
        val identifier = attributes.getValue("identifier").jsonPrimitive.content
        if (identifier in conflictingIdentifiers) {
            return errorResponse(
                status = 409,
                code = "ENTITY_ERROR.ATTRIBUTE.INVALID.DUPLICATE",
                title = "The provided entity includes an attribute with a value that has already been used",
                detail = "An App ID with Identifier '$identifier' is not available. Please enter a different string.",
            )
        }
        val bundleId = FakeBundleId(
            id = generateBundleIdResourceId(),
            identifier = identifier,
            name = attributes.getValue("name").jsonPrimitive.content,
            platform = attributes["platform"]?.jsonPrimitive?.content ?: "IOS",
            seedId = teamId,
        )
        bundleIds.add(bundleId)
        return AscResponse(201, buildJsonObject { put("data", bundleId.toResource()) })
    }

    private fun appsMatching(bundleIdFilter: String?): List<FakeApp> =
        if (bundleIdFilter == null) apps.toList() else apps.filter { it.bundleId == bundleIdFilter }

    private fun preReleaseVersions(versionFilter: String?): List<JsonObject> =
        uploads.map { it.version }
            .distinct()
            .filter { versionFilter == null || it == versionFilter }
            .map { version -> resource(preReleaseVersionId(version), "preReleaseVersions") { put("version", version) } }

    private fun findBuilds(query: Map<String, String>): AscResponse {
        val preReleaseVersionIds = query["filter[preReleaseVersion]"]?.split(",")
        val candidates = uploads.filter { preReleaseVersionIds == null || preReleaseVersionId(it.version) in preReleaseVersionIds }
        val buildNumber = query["filter[version]"]
            ?: return ok(listBody(candidates.map { it.toBuildResource(currentBuildState()) }))
        val upload = candidates.firstOrNull { it.buildNumber == buildNumber } ?: return ok(listBody([]))
        return when (val state = nextBuildState()) {
            DropConnection -> AscResponse.DroppedConnection
            is Int -> errorResponse(state)
            else -> ok(listBody([upload.toBuildResource(state.toString())]))
        }
    }

    private fun currentBuildState(): String = buildStates.first() as? String ?: "PROCESSING"

    private fun nextBuildState(): Any = if (buildStates.size > 1) buildStates.removeFirst() else buildStates.first()

    private fun uploadsMatching(query: Map<String, String>): List<FakeUpload> {
        val versionFilter = query["filter[cfBundleShortVersionString]"] ?: return uploads.toList()
        return uploads.filter { it.version == versionFilter }
    }

    private fun getBuildUpload(uploadId: String): AscResponse {
        val upload = uploads.firstOrNull { it.id == uploadId }
        if (upload == null || buildUploadLookupFails) {
            return errorResponse(404, detail = "There is no resource of type 'buildUploads' with id '$uploadId'")
        }
        return ok(buildJsonObject { put("data", upload.toBuildUploadResource()) })
    }
}

/**
 * A fake App Store Connect API server listening on [baseUrl].
 *
 * All `/v1` requests must be authorized with a JWT signed by the given [key]. Uploads can be registered via the
 * unauthenticated control endpoint `POST /__fake/uploads` with a body like `{"version":"1.0","build":"1"}`.
 */
internal class FakeAppStoreConnect(
    private val key: AscTestKey,
    private val state: FakeAscState,
) : AutoCloseable {
    private val recordedRequests = CopyOnWriteArrayList<AscRequest>()
    private val recordedUnexpectedRequests = CopyOnWriteArrayList<AscRequest>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 10).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** All requests received by the server, except the control endpoint ones. */
    val requests: List<AscRequest>
        get() = recordedRequests.toList()

    /** Uploads registered via the control endpoint. */
    val uploads: List<FakeUpload>
        get() = state.uploadsSnapshot()

    /** Requests that hit no route and got a 404. */
    val unexpectedRequests: List<AscRequest>
        get() = recordedUnexpectedRequests.toList()

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val body = exchange.requestBody.use { it.readBytes() }.decodeToString()
        val response = runCatching {
            if (method == "POST" && path == ControlUploadsPath) {
                registerUpload(body)
            } else {
                val authError = if (path.startsWith("/v1/")) {
                    key.verifyAuthorization(exchange.requestHeaders.getFirst("Authorization"))
                } else null
                answer(AscRequest(method, path, parseQuery(exchange.requestURI.rawQuery), body, authError))
            }
        }.getOrElse { errorResponse(500, detail = "Fake App Store Connect failed to handle the request: $it") }
        if (response === AscResponse.DroppedConnection) {
            // Closing the exchange before sending the response headers closes the underlying connection
            exchange.close()
        } else {
            exchange.respond(response)
        }
    }

    private fun registerUpload(body: String): AscResponse {
        val json = Json.parseToJsonElement(body).jsonObject
        val upload = state.registerUpload(
            version = json.getValue("version").jsonPrimitive.content,
            buildNumber = json.getValue("build").jsonPrimitive.content,
        )
        return ok(buildJsonObject { put("id", upload.id) })
    }

    private fun answer(request: AscRequest): AscResponse {
        recordedRequests.add(request)
        // Like the real App Store Connect, the response doesn't say what's wrong with the token (see AscRequest.authError)
        if (request.authError != null) return errorResponse(401)
        val response = if (request.path.startsWith("/v1/")) state.answer(request) else null
        if (response == null) {
            recordedUnexpectedRequests.add(request)
            return errorResponse(404, detail = "The fake has no route for ${request.method} ${request.path}")
        }
        return response
    }
}

private fun HttpExchange.respond(response: AscResponse) {
    val bytes = response.body.toString().encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(response.status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun parseQuery(rawQuery: String?): Map<String, String> =
    rawQuery.orEmpty()
        .split("&")
        .filter { it.isNotEmpty() }
        .associate { parameter ->
            val name = parameter.substringBefore("=")
            val value = parameter.substringAfter("=", missingDelimiterValue = "")
            name.urlDecoded() to value.urlDecoded()
        }

private fun String.urlDecoded(): String = URLDecoder.decode(this, Charsets.UTF_8)

private fun generateBundleIdResourceId(): String = UUID.randomUUID().toString().replace("-", "").take(10).uppercase()

private fun preReleaseVersionId(version: String) = "prv-$version"

private fun ok(body: JsonObject) = AscResponse(200, body)

private fun listBody(resources: List<JsonObject>): JsonObject = buildJsonObject {
    put("data", JsonArray(resources))
    putJsonObject("links") { put("next", JsonNull) }
}

private fun resource(id: String, type: String, attributes: JsonObjectBuilder.() -> Unit) =
    buildJsonObject {
        put("id", id)
        put("type", type)
        putJsonObject("attributes", attributes)
    }

private fun FakeBundleId.toResource() = resource(id, "bundleIds") {
    put("identifier", identifier)
    put("name", name)
    put("platform", platform)
    put("seedId", seedId)
}

private fun FakeApp.toResource() = resource(id, "apps") {
    put("name", name)
    put("bundleId", bundleId)
    put("sku", sku)
}

private fun FakeUpload.toBuildResource(processingState: String) = resource("build-$id", "builds") {
    put("version", buildNumber)
    put("processingState", processingState)
}

private fun FakeUpload.toBuildUploadResource() = resource(id, "buildUploads") {
    put("cfBundleShortVersionString", version)
    put("cfBundleVersion", buildNumber)
}

/**
 * An error response in the format of App Store Connect. The default texts for 401 and 5xx are the ones Apple sends.
 */
private fun errorResponse(
    status: Int,
    code: String = defaultErrorCode(status),
    title: String = defaultErrorTitle(status),
    detail: String = defaultErrorDetail(status),
) = AscResponse(status, buildJsonObject {
    putJsonArray("errors") {
        add(buildJsonObject {
            put("status", status.toString())
            put("code", code)
            put("title", title)
            put("detail", detail)
        })
    }
})

private fun defaultErrorCode(status: Int): String = when (status) {
    401 -> "NOT_AUTHORIZED"
    403 -> "FORBIDDEN_ERROR"
    404 -> "NOT_FOUND"
    409 -> "ENTITY_ERROR"
    429 -> "RATE_LIMIT_EXCEEDED"
    else -> "UNEXPECTED_ERROR"
}

private fun defaultErrorTitle(status: Int): String = when {
    status == 401 -> "Authentication credentials are missing or invalid."
    status >= 500 -> "An unexpected error occurred."
    else -> "The request failed with status $status"
}

private fun defaultErrorDetail(status: Int): String = when {
    status == 401 -> "Provide a properly configured and signed bearer token, and make sure that it has not expired. " +
            "Learn more about Generating Tokens for API Requests https://developer.apple.com/go/?id=api-generating-tokens"
    status >= 500 -> "An unexpected error occurred on the server side. " +
            "If this issue continues, contact us at https://developer.apple.com/contact/."
    else -> "The fake App Store Connect server responded with status $status."
}
