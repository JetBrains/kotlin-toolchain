/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.crypto.pgp

import org.bouncycastle.openpgp.api.KeyPassphraseProvider
import org.bouncycastle.openpgp.api.OpenPGPApi
import org.bouncycastle.openpgp.api.OpenPGPKey.OpenPGPSecretKey
import org.bouncycastle.openpgp.api.OpenPGPKeyReader
import org.bouncycastle.openpgp.api.OpenPGPSignature
import org.bouncycastle.openpgp.api.bc.BcOpenPGPApi
import org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation
import org.bouncycastle.openpgp.api.exception.KeyPassphraseException
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

/**
 * An interface to sign files using the PGP standard.
 */
interface PgpSigner {

    /**
     * Signs the given [inputFile], and writes the generated signature in ASCII-armored PGP format to
     * [outputSignatureFile].
     *
     * @throws PgpSigningException if signing fails
     */
    suspend fun sign(inputFile: Path, outputSignatureFile: Path)

    companion object {
        /**
         * Creates a [PgpSigner] that uses the given PGP [signingKey] for signing.
         * If the [signingKey] is encrypted, the [keyPassphrase] to decrypt it must be provided.
         *
         * A PGP key can contain several (sub-)keys, in which case the one to sign with is selected as follows:
         *  * if [signingKeyId] is provided, the (sub-)key with that ID is used;
         *  * otherwise, the only signing-capable (sub-)key that has secret key material is used.
         *
         * @throws PgpKeyParsingException if the key could not be parsed
         * @throws PgpSigningKeySelectionException if the (sub-)key to sign with cannot be determined
         */
        fun bouncyCastle(
            signingKey: AsciiArmoredPgpKey,
            keyPassphrase: CharArray?,
            signingKeyId: PgpKeyId? = null,
        ): PgpSigner {
            val implementation = BcOpenPGPImplementation()
            val policy = implementation.policy()
            val key = try {
                OpenPGPKeyReader(implementation, policy).parseKey(signingKey.text)
            } catch (e: IOException) {
                throw PgpKeyParsingException(cause = e)
            }

            return BouncyCastlePgpSigner(
                openPgpApi = BcOpenPGPApi(implementation, policy),
                signingKey = key.selectSigningKey(signingKeyId, policy),
                pgpKeyPassphrase = keyPassphrase,
            )
        }
    }
}

/**
 * A PGP key in ASCII-armored format.
 *
 * This is the text format that looks like this:
 * ```
 * -----BEGIN PGP PRIVATE KEY BLOCK-----
 *
 * lQOYBF+exampleBCAC7V...snip...9sWkZz2q0kR
 * =AbCd
 *
 * -----END PGP PRIVATE KEY BLOCK-----
 * ```
 */
@JvmInline
value class AsciiArmoredPgpKey(val text: String)

/**
 * Thrown when an ASCII-armored PGP key cannot be parsed.
 */
class PgpKeyParsingException(message: String? = null, cause: Throwable) : Exception(message ?: cause.message, cause)

/**
 * Thrown when signing fails.
 */
open class PgpSigningException(cause: Throwable) : Exception(
    cause.toString(),
    cause
)

class PgpSigningKeyPassphraseException(val passphrasePresent: Boolean, cause: Throwable) : PgpSigningException(cause)

private class BouncyCastlePgpSigner(
    private val openPgpApi: OpenPGPApi,
    /**
     * The private PGP (sub-)key to use for signing artifacts.
     * If it is encrypted, the passphrase must also be provided (see [pgpKeyPassphrase]).
     */
    private val signingKey: OpenPGPSecretKey,
    /**
     * The passphrase to use for decrypting the private PGP key (should only be provided if [signingKey] is encrypted).
     */
    private val pgpKeyPassphrase: CharArray?,
) : PgpSigner {

    override suspend fun sign(inputFile: Path, outputSignatureFile: Path) {
        val signature = sign(inputFile)
        outputSignatureFile.writeText(signature.toAsciiArmoredString())
    }

    private fun sign(file: Path): OpenPGPSignature = try {
        // sign() mutates the generator, so we must create a new one every time, unfortunately.
        // We add the resolved (sub-)key directly instead of the whole PGP key, because BouncyCastle's default subkey
        // selection would pick every signing-capable (sub-)key, including stubs without secret key material.
        val signingGenerator = openPgpApi.createDetachedSignature()
            .addSigningKey(signingKey, KeyPassphraseProvider { pgpKeyPassphrase }, null)

        file.inputStream().use { fileStream ->
            // we are guaranteed to have a single signature because we provided a single signing key
            signingGenerator.sign(fileStream).single()
        }
    } catch (e: KeyPassphraseException) {
        throw PgpSigningKeyPassphraseException(passphrasePresent = pgpKeyPassphrase != null, e)
    } catch (e: Exception) {
        throw PgpSigningException(e)
    }
}
