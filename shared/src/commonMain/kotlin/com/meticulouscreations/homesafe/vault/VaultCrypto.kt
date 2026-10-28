package com.meticulouscreations.homesafe.vault

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/**
 * The household key vault's cryptography (docs/key-vault.md), all of it on the phone: the relay
 * only ever stores what comes out of here. Everything is standard and done by the platform (the
 * JDK on Android; CryptoKit, and CommonCrypto for PBKDF2, on iOS):
 *
 * - A *login key*: PBKDF2-SHA-256 of the Frigate password, salted with the username.
 * - A member's P-256 key pair, the private half sealed with the login key (AES-256-GCM).
 * - The *household key*: 256 random bits, given to a member by ECDH with a one-off key pair
 *   against their public key, HKDF-SHA-256, then AES-256-GCM: an ECIES "grant".
 * - Each secret sealed with the household key (AES-256-GCM), its name bound in as associated
 *   data so one can't be passed off as another.
 *
 * Blobs are base64; an AES-GCM blob is the IV, the ciphertext and the tag, as the library lays them out.
 */
object VaultCrypto {
    private val provider get() = CryptographyProvider.Default
    private val aes get() = provider.get(AES.GCM)
    private val ecdh get() = provider.get(ECDH)

    /** A member's key pair, the private half only ever kept in memory once opened. */
    class Member(val public: String, val sealedPrivate: String, internal val private: ECDH.PrivateKey)

    /** The household key, sealed to one member's public key. */
    class Grant(val ephemeral: String, val box: String)

    /**
     * The key a Frigate login seals its member key pair with. Slow on purpose (about a second
     * on a phone): it is worked out once per sign-in, and makes guessing the password from a
     * copy of the vault as slow.
     */
    suspend fun loginKey(username: String, password: String, iterations: Int = LOGIN_ITERATIONS): ByteArray {
        val salt = provider.get(SHA256).hasher().hash("$CONTEXT/login/$username".encodeToByteArray())
        return provider.get(PBKDF2).secretDerivation(SHA256, iterations, 256.bits, salt).deriveSecretToByteArray(password.encodeToByteArray())
    }

    suspend fun newMember(loginKey: ByteArray): Member {
        val pair = ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
        val sealed = seal(loginKey, pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER), MEMBER_DATA)
        return Member(encode(pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)), encode(sealed), pair.privateKey)
    }

    /** The member key pair [sealedPrivate] holds; null when [loginKey] doesn't open it (the password has changed). */
    suspend fun openMember(loginKey: ByteArray, public: String, sealedPrivate: String): Member? {
        val der = open(loginKey, decode(sealedPrivate) ?: return null, MEMBER_DATA) ?: return null
        val private = runCatching { ecdh.privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.DER, der) }.getOrNull() ?: return null
        return Member(public, sealedPrivate, private)
    }

    suspend fun newHouseholdKey(): ByteArray = aes.keyGenerator(AES.Key.Size.B256).generateKey().encodeToByteArray(AES.Key.Format.RAW)

    /** [householdKey] sealed so that only the holder of [memberPublic]'s private key can open it. */
    suspend fun grant(householdKey: ByteArray, memberPublic: String): Grant {
        val member = ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, decode(memberPublic) ?: error("Not base64"))
        val oneOff = ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
        val ephemeral = oneOff.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        val wrapping = grantKey(oneOff.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(member), ephemeral)
        return Grant(encode(ephemeral), encode(seal(wrapping, householdKey, GRANT_DATA)))
    }

    /** The household key in [grant]; null when it wasn't made for [member]. */
    suspend fun openGrant(member: Member, grant: Grant): ByteArray? {
        val ephemeral = decode(grant.ephemeral) ?: return null
        val sender = runCatching { ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, ephemeral) }.getOrNull() ?: return null
        val wrapping = grantKey(member.private.sharedSecretGenerator().generateSharedSecretToByteArray(sender), ephemeral)
        return open(wrapping, decode(grant.box) ?: return null, GRANT_DATA)
    }

    suspend fun sealSecret(householdKey: ByteArray, name: String, value: String): String = encode(seal(householdKey, value.encodeToByteArray(), secretData(name)))

    /** The secret [box] holds under [name]; null when the household key or the name doesn't match. */
    suspend fun openSecret(householdKey: ByteArray, name: String, box: String): String? = open(householdKey, decode(box) ?: return null, secretData(name))?.decodeToString()

    private suspend fun grantKey(shared: ByteArray, ephemeral: ByteArray): ByteArray =
        provider.get(HKDF).secretDerivation(SHA256, 256.bits, salt = ephemeral, info = "$CONTEXT/grant".encodeToByteArray()).deriveSecretToByteArray(shared)

    private suspend fun seal(key: ByteArray, plaintext: ByteArray, associated: ByteArray): ByteArray =
        aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher().encrypt(plaintext, associated)

    private suspend fun open(key: ByteArray, sealed: ByteArray, associated: ByteArray): ByteArray? =
        runCatching { aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher().decrypt(sealed, associated) }.getOrNull()

    private fun secretData(name: String) = "$CONTEXT/secret/$name".encodeToByteArray()

    private fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    private fun decode(text: String): ByteArray? = runCatching { Base64.decode(text) }.getOrNull()

    /** Bound into everything sealed, so a blob made for one purpose (or a later format) opens for nothing else. */
    private const val CONTEXT = "homesafe-vault/v1"
    private val MEMBER_DATA = "$CONTEXT/member".encodeToByteArray()
    private val GRANT_DATA = "$CONTEXT/grant".encodeToByteArray()

    /** OWASP's figure for PBKDF2-SHA-256. */
    const val LOGIN_ITERATIONS = 600_000
}
