/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.crypto.pgp

import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.S2K
import org.bouncycastle.bcpg.SecretKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.api.OpenPGPCertificate.OpenPGPComponentKey
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.bouncycastle.openpgp.api.OpenPGPKeyGenerator
import org.bouncycastle.openpgp.api.bc.BcOpenPGPApi

private const val TEST_USER_ID = "Test Key <test@example.org>"

private val openPgpApi = BcOpenPGPApi()

/**
 * The version of the OpenPGP key packets, which notably defines how fingerprints and key IDs are derived.
 *
 * In v4 keys, the key ID is the last 8 bytes of the 20-byte SHA-1 fingerprint, while in v6 keys it is the first
 * 8 bytes of the 32-byte SHA-256 fingerprint.
 */
internal enum class PgpKeyVersion(val packetVersion: Int) {
    V4(PublicKeyPacket.VERSION_4),
    V6(PublicKeyPacket.VERSION_6),
}

private fun PgpKeyVersion.keyGenerator(): OpenPGPKeyGenerator = openPgpApi.generateKey(packetVersion)

/**
 * Generates a key consisting of a single sign-and-certify (`[SC]`) primary key, without any subkey.
 */
// Note: the passphrase is passed as a String because BouncyCastle's build() wipes the given char array.
internal fun generateSignOnlyKey(version: PgpKeyVersion, passphrase: String? = null): OpenPGPKey =
    version.keyGenerator().signOnlyKey().addUserId(TEST_USER_ID).build(passphrase?.toCharArray())

/**
 * Generates a key with the layout that `gpg` creates by default: a certify-only (`[C]`) primary key, a signing
 * (`[S]`) subkey, and an encryption (`[E]`) subkey.
 */
internal fun generateCertOnlyKeyWithSigningSubkey(version: PgpKeyVersion): OpenPGPKey =
    version.keyGenerator().classicKey(TEST_USER_ID).build()

/**
 * Generates a key with two signing-capable keys: a sign-and-certify (`[SC]`) primary key, and a signing (`[S]`) subkey.
 */
internal fun generateSigningKeyWithSigningSubKey(version: PgpKeyVersion): OpenPGPKey =
    version.keyGenerator().signOnlyKey().addSigningSubkey().addUserId(TEST_USER_ID).build()

/**
 * Generates a key that cannot sign anything: a certify-only (`[C]`) primary key with a single encryption (`[E]`)
 * subkey.
 */
internal fun generateEncryptionOnlyKey(version: PgpKeyVersion): OpenPGPKey =
    version.keyGenerator().withPrimaryKey().addEncryptionSubkey().addUserId(TEST_USER_ID).build()

/**
 * Returns a copy of this key in which the primary key's secret material is replaced by a GNU-dummy stub.
 *
 * This is what `gpg --export-secret-subkeys` produces when the primary key is kept offline or on a smart card:
 * the primary key packet is still present (with its flags and self-signatures), but holds no private key.
 */
internal fun OpenPGPKey.withStubbedPrimaryKey(): OpenPGPKey {
    val keyRing = pgpSecretKeyRing
    val primaryKey = keyRing.secretKey
    val stubbedPrimaryKey = PGPSecretKey(
        SecretKeyPacket(
            primaryKey.publicKey.publicKeyPacket,
            SymmetricKeyAlgorithmTags.NULL,
            SecretKeyPacket.USAGE_SHA1,
            S2K.gnuDummyS2K(S2K.GNUDummyParams.noPrivateKey()),
            null,
            ByteArray(0),
        ),
        primaryKey.publicKey,
    )
    return OpenPGPKey(PGPSecretKeyRing.insertSecretKey(keyRing, stubbedPrimaryKey))
}

internal fun OpenPGPKey.asciiArmored() = AsciiArmoredPgpKey(toAsciiArmoredString())

/**
 * The full fingerprint of this key, in the uppercase hexadecimal format that we report in error messages.
 */
internal val OpenPGPComponentKey.id: PgpKeyId
    get() = PgpKeyId(keyIdentifier.fingerprint.toHexString())

/**
 * The only signing-capable subkey of this key (fails if there isn't exactly one).
 */
internal val OpenPGPKey.signingSubkey: OpenPGPComponentKey
    get() = signingKeys.single { !it.isPrimaryKey }

internal val OpenPGPKey.primaryComponentKey: OpenPGPComponentKey
    get() = primaryKey
