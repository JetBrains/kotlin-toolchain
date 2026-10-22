/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private const val ExpectedAudience = "appstoreconnect-v1"
private val MaxTokenLifetime = 20.minutes

/**
 * A test App Store Connect API key: an EC P-256 key pair with a key ID and an issuer ID.
 * The private key can be written as a `.p8` file, and JWTs sent by the client can be verified with the public key.
 */
internal class AscTestKey(
    val keyId: String = "ABCDE12345",
    val issuerId: String = "69a6de7e-1111-47e3-e053-5b8c7c11a4d1",
) {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /**
     * Writes the private key in the PEM-encoded PKCS#8 `.p8` format to the given [path], and returns the [path].
     */
    fun writeP8(path: Path): Path {
        val base64Lines = Base64.encode(keyPair.private.encoded).chunked(64).joinToString("\n")
        path.writeText("-----BEGIN PRIVATE KEY-----\n$base64Lines\n-----END PRIVATE KEY-----\n")
        return path
    }

    /**
     * Verifies the given `Authorization` header value.
     * Returns `null` if it contains a valid JWT for this key, or a human-readable reason why it is invalid.
     */
    fun verifyAuthorization(authorizationHeader: String?): String? {
        if (authorizationHeader == null) return "missing Authorization header"
        if (!authorizationHeader.startsWith("Bearer ")) return "Authorization header is not a Bearer token"
        val parts = authorizationHeader.removePrefix("Bearer ").split(".")
        if (parts.size != 3) return "JWT must have 3 parts, but has ${parts.size}"
        val headerPart = parts[0]
        val payloadPart = parts[1]
        val signaturePart = parts[2]
        val header = decodeJsonPart(headerPart) ?: return "JWT header is not valid base64url JSON"
        val payload = decodeJsonPart(payloadPart) ?: return "JWT payload is not valid base64url JSON"
        return verifyHeader(header)
            ?: verifyPayload(payload)
            ?: verifySignature(signingInput = "$headerPart.$payloadPart", signaturePart = signaturePart)
    }

    private fun verifyHeader(header: JsonObject): String? = when {
        header.string("alg") != "ES256" -> "JWT alg is '${header.string("alg")}', expected 'ES256'"
        header.string("kid") != keyId -> "JWT kid is '${header.string("kid")}', expected '$keyId'"
        else -> null
    }

    private fun verifyPayload(payload: JsonObject): String? {
        val iat = payload.long("iat") ?: return "JWT iat is missing"
        val exp = payload.long("exp") ?: return "JWT exp is missing"
        val now = Clock.System.now().epochSeconds
        return when {
            payload.string("iss") != issuerId -> "JWT iss is '${payload.string("iss")}', expected '$issuerId'"
            payload.string("aud") != ExpectedAudience -> "JWT aud is '${payload.string("aud")}', expected '$ExpectedAudience'"
            exp <= now -> "JWT is expired (exp=$exp, now=$now)"
            exp - iat > MaxTokenLifetime.inWholeSeconds -> "JWT lifetime exceeds $MaxTokenLifetime (iat=$iat, exp=$exp)"
            else -> null
        }
    }

    private fun verifySignature(signingInput: String, signaturePart: String): String? {
        val signatureBytes = runCatching { base64Url.decode(signaturePart) }.getOrNull()
            ?: return "JWT signature is not valid base64url"
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(keyPair.public)
                update(signingInput.encodeToByteArray())
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        return if (valid) null else "JWT signature is invalid"
    }
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private fun decodeJsonPart(part: String): JsonObject? = runCatching {
    Json.parseToJsonElement(base64Url.decode(part).decodeToString()).jsonObject
}.getOrNull()

private fun JsonObject.string(key: String): String? = runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

private fun JsonObject.long(key: String): Long? = runCatching { get(key)?.jsonPrimitive?.longOrNull }.getOrNull()
