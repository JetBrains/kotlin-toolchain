/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.crypto.pgp

import org.bouncycastle.openpgp.api.OpenPGPKey
import org.bouncycastle.openpgp.api.OpenPGPKeyReader
import org.bouncycastle.openpgp.api.OpenPGPSignature
import org.bouncycastle.openpgp.api.bc.BcOpenPGPApi
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
         * @throws PgpKeyParsingException if the key could not be parsed
         */
        fun bouncyCastle(signingKey: AsciiArmoredPgpKey, keyPassphrase: CharArray?): PgpSigner {
            val key = try {
                OpenPGPKeyReader().parseKey(signingKey.text)
            } catch (e: IOException) {
                throw PgpKeyParsingException(e)
            }

            return BouncyCastlePgpSigner(key, keyPassphrase)
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
class PgpKeyParsingException(cause: Throwable) : Exception(cause.message, cause)

/**
 * Thrown when signing fails.
 */
open class PgpSigningException(cause: Throwable) : Exception(
    cause.toString(),
    cause
)

class PgpSigningKeyPassphraseException(val passphrasePresent: Boolean, cause: Throwable) : PgpSigningException(cause)

private class BouncyCastlePgpSigner(
    /**
     * The private PGP key to use for signing artifacts.
     * If it is encrypted, the passphrase must also be provided (see [pgpKeyPassphrase]).
     */
    private val signingKey: OpenPGPKey,
    /**
     * The passphrase to use for decrypting the private PGP key (should only be provided if [signingKey] is encrypted).
     */
    private val pgpKeyPassphrase: CharArray?,
) : PgpSigner {

    private val openPgpApi = BcOpenPGPApi()

    override suspend fun sign(inputFile: Path, outputSignatureFile: Path) {
        val signature = sign(inputFile)
        outputSignatureFile.writeText(signature.toAsciiArmoredString())
    }

    private fun sign(file: Path): OpenPGPSignature = try {
        // sign() mutates the generator, so we must create a new one every time, unfortunately
        val signingGenerator = openPgpApi.createDetachedSignature().addSigningKey(signingKey) { pgpKeyPassphrase }

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
