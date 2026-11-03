/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("SSBasedInspection") // runTest and MDC doesn't apply here

package org.jetbrains.amper.crypto.pgp

import kotlinx.coroutines.test.runTest
import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPSignature.OpenPGPDocumentSignature
import org.bouncycastle.openpgp.api.bc.BcOpenPGPApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@ParameterizedClass
@EnumSource(PgpKeyVersion::class)
internal class PgpSignerTest(private val keyVersion: PgpKeyVersion) {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `signs with a single sign-only key`() = runTest {
        val key = generateSignOnlyKey(keyVersion)
        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = null)

        val signature = signer.signAndVerify(key)
        assertEquals(key.keyIdentifier, signature.issuer.keyIdentifier)
    }

    @Test
    fun `signs with a passphrase-protected key`() = runTest {
        val key = generateSignOnlyKey(keyVersion, passphrase = "s3cr3t")
        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = "s3cr3t".toCharArray())

        val signature = signer.signAndVerify(key)
        assertEquals(key.keyIdentifier, signature.issuer.keyIdentifier)
    }

    @Test
    fun `signs with the signing subkey of a key with a certify-only primary key`() = runTest {
        val key = generateCertOnlyKeyWithSigningSubkey(keyVersion)
        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = null)

        val signature = signer.signAndVerify(key)
        assertEquals(key.signingSubkey.keyIdentifier, signature.issuer.keyIdentifier)
    }

    @Test
    fun `signs with the signing subkey when the primary key is a stub without secret material`() = runTest {
        // This is what `gpg --export-secret-subkeys '<subkey>!'` produces when the primary key is kept offline or
        // on a smart card. The stub primary key is sign-capable, but cannot actually sign anything.
        val key = generateSigningKeyWithSigningSubKey(keyVersion).withStubbedPrimaryKey()
        assertEquals(2, key.signingKeys.size, "the stub primary key should still be advertised as signing-capable")

        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = null)

        val signature = signer.signAndVerify(key)

        assertEquals(key.signingSubkey.keyIdentifier, signature.issuer.keyIdentifier)
    }

    @Test
    fun `selects the requested signing subkey by full fingerprint, long ID, or short ID`() = runTest {
        val key = generateSigningKeyWithSigningSubKey(keyVersion)
        val fingerprint = key.signingSubkey.keyIdentifier.fingerprint.toHexString()
        val longId = KeyIdentifier(fingerprint).keyId.toHexString()

        val acceptedForms = listOf(
            fingerprint,
            longId, // long key ID
            longId.takeLast(8), // short key ID
            "0x${longId.lowercase()}", // as printed by some tools
            fingerprint.chunked(4).joinToString(" "), // as printed by `gpg --fingerprint`
        )
        acceptedForms.forEach { keyIdForm ->
            val signer = PgpSigner.bouncyCastle(key.asciiArmored(), null, PgpKeyId(keyIdForm))

            val signature = signer.signAndVerify(key, signatureFileName = "sig-$keyIdForm.asc")
            assertEquals(key.signingSubkey.keyIdentifier, signature.issuer.keyIdentifier, "for key ID '$keyIdForm'")
        }
    }

    @Test
    fun `selects the requested primary key even when the key has a signing subkey`() = runTest {
        val key = generateSigningKeyWithSigningSubKey(keyVersion)
        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), null, key.primaryComponentKey.id)

        val signature = signer.signAndVerify(key)
        assertEquals(key.primaryComponentKey.keyIdentifier, signature.issuer.keyIdentifier)
    }

    @Test
    fun `fails when the requested key ID matches no signing key`() {
        val key = generateCertOnlyKeyWithSigningSubkey(keyVersion)

        val exception = assertFailsWith<SelectedKeyNotUsableException> {
            PgpSigner.bouncyCastle(key.asciiArmored(), null, PgpKeyId("DEADBEEF"))
        }
        assertEquals([key.signingSubkey.id], exception.availableSigningKeyIds)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "not-a-hex-id", // completely wrong
        "ZZZZZZZZZZZZZZZZ", // right length but not hex
        "DEADBEE", // hex but odd number of hex digits
        "ZZZZZZZZ", // 8 non-hex digits (short ID path)
    ])
    fun `fails with a dedicated exception when the requested key ID is malformed`(malformedId: String) {
        val key = generateSignOnlyKey(keyVersion)
        if (malformedId.length == 8) {
            // Special case for 8 hex digits to test the short ID.
            // It currently fails with a different exception, but this is totally acceptable.
            assertFailsWith<SelectedKeyNotUsableException>("for key ID '$malformedId'") {
                PgpSigner.bouncyCastle(key.asciiArmored(), null, PgpKeyId(malformedId))
            }
        } else {
            assertFailsWith<InvalidKeyIdException>("for key ID '$malformedId'") {
                PgpSigner.bouncyCastle(key.asciiArmored(), null, PgpKeyId(malformedId))
            }
        }
    }

    @Test
    fun `fails when the requested key ID points at a stub key without secret material`() {
        val key = generateSigningKeyWithSigningSubKey(keyVersion).withStubbedPrimaryKey()

        val exception = assertFailsWith<SelectedKeyNotUsableException> {
            PgpSigner.bouncyCastle(key.asciiArmored(), null, key.primaryComponentKey.id)
        }
        // The message must say that the key was found but is unusable, not that it is missing altogether.
        assertContains(exception.message.orEmpty(), "is only present as a stub")
        assertEquals([key.signingSubkey.id], exception.availableSigningKeyIds)
    }

    @Test
    fun `fails when several signing keys could be used and none is requested`() {
        val key = generateSigningKeyWithSigningSubKey(keyVersion)

        val exception = assertFailsWith<AmbiguousSigningKeyException> {
            PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = null)
        }
        assertEquals(
            setOf(key.primaryComponentKey.id, key.signingSubkey.id),
            exception.candidateKeyIds.toSet(),
        )
    }

    @Test
    fun `fails when the key has no signing-capable subkey at all`() {
        val key = generateEncryptionOnlyKey(keyVersion)

        assertFailsWith<NoUsableSigningKeyException> {
            PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = null)
        }
    }

    @Test
    fun `fails with a dedicated exception when the passphrase is incorrect`() = runTest {
        val key = generateSignOnlyKey(keyVersion, passphrase = "s3cr3t")
        val signer = PgpSigner.bouncyCastle(key.asciiArmored(), keyPassphrase = "wrong".toCharArray())

        val exception = assertFailsWith<PgpSigningKeyPassphraseException> { signer.signAndVerify(key) }
        assertTrue(exception.passphrasePresent)
    }

    @Test
    fun `fails with a dedicated exception when the key cannot be parsed`() {
        assertFailsWith<PgpKeyParsingException> {
            PgpSigner.bouncyCastle(AsciiArmoredPgpKey("not a PGP key at all"), keyPassphrase = null)
        }
    }

    private suspend fun PgpSigner.signAndVerify(
        certificate: OpenPGPCertificate,
        signatureFileName: String = "data.txt.asc",
    ): OpenPGPDocumentSignature {
        val dataFile = (tempDir / "data.txt").apply { writeText("some content to sign") }
        val signatureFile = tempDir / signatureFileName

        sign(dataFile, outputSignatureFile = signatureFile)

        val verifier = signatureFile.inputStream().use { signatureStream ->
            BcOpenPGPApi().verifyDetachedSignature()
                .addSignatures(signatureStream)
                .addVerificationCertificate(certificate)
        }
        val signatures = dataFile.inputStream().use { dataStream -> verifier.process(dataStream) }
        val signature = signatures.single()
        assertTrue(signature.isValid, "the generated signature should be valid")
        return signature
    }
}
