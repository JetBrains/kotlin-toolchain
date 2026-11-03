/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.crypto.pgp

import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.bouncycastle.openpgp.api.OpenPGPKey.OpenPGPSecretKey
import org.bouncycastle.openpgp.api.OpenPGPPolicy
import org.bouncycastle.util.encoders.DecoderException

/**
 * The hexadecimal identifier of a PGP key or subkey.
 *
 * All the forms printed by `gpg --list-keys` are accepted: a short key ID (8 hex digits), a long key ID (16 hex
 * digits), or a full fingerprint.
 *
 * The value is case-insensitive, ignores whitespace, and ignores leading `0x`.
 */
class PgpKeyId(
    /**
     * The short ID (8 hex digits), long ID (16 hex digits), or full fingerprint.
     */
    private val idOrFingerprintHex: String,
) {
    private val normalizedHex: String
        get() = idOrFingerprintHex.filter { !it.isWhitespace() }.uppercase().removePrefix("0X")

    private val isShortId: Boolean
        get() = normalizedHex.length == 8

    /**
     * Whether this key ID matches the given BouncyCastle [identifier].
     */
    internal fun matches(identifier: KeyIdentifier): Boolean = if (isShortId) {
        // KeyIdentifier doesn't support matching short IDs, so we check manually if `hex` is a short ID
        identifier.keyId.toHexString().endsWith(normalizedHex, ignoreCase = true)
    } else {
        try {
            KeyIdentifier(normalizedHex).matchesExplicit(identifier)
        } catch (e: DecoderException) {
            throw InvalidKeyIdException("Invalid PGP key ID or fingerprint: $normalizedHex (${e.cause?.message ?: e.message})")
        }
    }

    override fun equals(other: Any?): Boolean = normalizedHex == (other as? PgpKeyId)?.normalizedHex

    override fun hashCode(): Int = normalizedHex.hashCode()

    override fun toString(): String = normalizedHex
}

/**
 * Selects the (sub-)key of this PGP key to sign with.
 *
 * If [requestedKeyId] is null, a single (sub-)key with usable secret key material is expected.
 * If multiple suitable keys are found, this method throws [AmbiguousSigningKeyException].
 *
 * @throws AmbiguousSigningKeyException if multiple suitable keys are found
 * @throws NoUsableSigningKeyException if no suitable key is found (the given ID was not found, or no suitable key is
 *   available at all)
 */
internal fun OpenPGPKey.selectSigningKey(requestedKeyId: PgpKeyId?, policy: OpenPGPPolicy): OpenPGPSecretKey {
    // This is the same base selection as BouncyCastle's default SubkeySelector, so we never accept a key that
    // BouncyCastle would reject anyway when generating the signature.
    // Key rings may also carry public-only subkeys, for which getSecretKey() returns null. They cannot sign.
    val signingKeys = signingKeys
        .filter { policy.isAcceptablePublicKey(it.pgpPublicKey) }
        .mapNotNull { getSecretKey(it) }
    if (signingKeys.isEmpty()) {
        throw NoUsableSigningKeyException("the provided PGP key doesn't contain any signing-capable (sub-)key")
    }
    return if (requestedKeyId != null) {
        signingKeys.selectById(requestedKeyId)
    } else {
        signingKeys.selectOnlyUsableKey()
    }
}

private fun List<OpenPGPSecretKey>.selectById(requestedKeyId: PgpKeyId): OpenPGPSecretKey {
    val matches = filter { requestedKeyId.matches(it.keyIdentifier) }
    if (matches.isEmpty()) {
        throw SelectedKeyNotUsableException(
            message = "the provided PGP key doesn't contain any signing (sub-)key with ID '$requestedKeyId'",
            availableSigningKeyIds = usableKeyIds(),
        )
    }
    if (matches.size > 1) {
        throw AmbiguousSigningKeyException(candidateKeyIds = matches.map { it.id })
    }
    val match = matches.single()
    if (!match.hasSecretMaterial) {
        throw SelectedKeyNotUsableException(
            message = "the signing (sub-)key with ID '$requestedKeyId' is only present as a stub in the provided " +
                    "PGP key, without any secret key material, so it cannot be used for signing",
            availableSigningKeyIds = usableKeyIds(),
        )
    }
    return match
}

private fun List<OpenPGPSecretKey>.selectOnlyUsableKey(): OpenPGPSecretKey {
    // Exports created with `gpg --export-secret-subkeys` contain stub keys without secret key material (usually the
    // primary key, when it is kept offline or on a smart card). Such keys cannot sign, so we must not select them.
    val usableKeys = filter { it.hasSecretMaterial }
    if (usableKeys.isEmpty()) {
        throw NoUsableSigningKeyException(
            message = "all the signing (sub-)keys of the provided PGP key are stubs without secret key material " +
                    "(${map { it.id }.joinToString()}), so none of them can be used for signing",
        )
    }
    return usableKeys.singleOrNull() ?: throw AmbiguousSigningKeyException(candidateKeyIds = usableKeys.map { it.id })
}

private fun List<OpenPGPSecretKey>.usableKeyIds() = filter { it.hasSecretMaterial }.map { it.id }

private val OpenPGPSecretKey.id: PgpKeyId
    get() = PgpKeyId(this.keyIdentifier.fingerprint.toHexString())

/**
 * Whether this key actually holds the private key material, as opposed to being a mere stub for a key that is kept
 * offline or on a hardware token.
 */
private val OpenPGPSecretKey.hasSecretMaterial: Boolean
    get() = !pgpSecretKey.isPrivateKeyEmpty

/**
 * Thrown when the (sub-)key to sign with cannot be determined from the provided PGP key.
 */
sealed class PgpSigningKeySelectionException(message: String) : Exception(message)

/**
 * Thrown when the provided PGP key ID cannot be parsed.
 */
class InvalidKeyIdException(message: String) : PgpSigningKeySelectionException(message)

/**
 * Thrown when the provided key ID doesn't match a usable signing (sub-)key of the provided PGP key.
 */
class SelectedKeyNotUsableException(
    message: String,
    /**
     * The IDs of the signing (sub-)keys of the provided PGP key that could be used instead, if any.
     */
    val availableSigningKeyIds: List<PgpKeyId>,
) : PgpSigningKeySelectionException(message)

/**
 * Thrown when the provided PGP key doesn't contain any signing (sub-)key that can be used for signing.
 */
class NoUsableSigningKeyException(message: String) : PgpSigningKeySelectionException(message)

/**
 * Thrown when several signing (sub-)keys of the provided PGP key could be used, and none was explicitly requested.
 */
class AmbiguousSigningKeyException(
    /**
     * The IDs of the signing (sub-)keys that could be used.
     */
    val candidateKeyIds: List<PgpKeyId>,
) : PgpSigningKeySelectionException(
    "the provided PGP key contains ${candidateKeyIds.size} signing (sub-)keys that could be used, " +
            "so the one to sign with must be specified explicitly"
)
