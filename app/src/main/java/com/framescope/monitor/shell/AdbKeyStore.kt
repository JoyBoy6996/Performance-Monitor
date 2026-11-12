package com.framescope.monitor.shell

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Host side of the ADB key exchange.
 *
 * `adbd` does not accept a normal X.509 key. It expects the 524 byte "Android
 * pubkey" blob that the platform's own `adb` tool uses, base64 encoded. The
 * layout (little endian integers, matching AOSP
 * `libcrypto_utils/android_pubkey.cpp`) is:
 *
 * ```
 * uint32   modulus size in words        (64 for a 2048 bit key)
 * uint32   n0inv = -1 / n[0] mod 2^32
 * byte[256] modulus, little endian
 * byte[256] rr = R^2 mod n, little endian
 * uint32   public exponent
 * ```
 *
 * The same key also has to survive app restarts, otherwise the phone asks for
 * "Allow USB debugging?" on every launch, so the pair is kept in the app's
 * private storage.
 */
class AdbKeyStore(private val directory: File) {

    private val privateFile = File(directory, "adb_host_key.pk8")
    private val publicFile = File(directory, "adb_host_key.pk8.pub")

    /**
     * Remembers that the phone keeps this key.
     *
     * The stamp is written after the key was found in the phone's own key
     * store, so the app knows it will not have to ask for the debugging
     * permission again.
     */
    private val storedStamp = File(directory, "authorised.stamp")

    @Volatile
    private var keyPair: KeyPair? = null

    init {
        runCatching { load() }
    }

    /** True when a key pair is loaded or can be created on disk. */
    val hasKey: Boolean get() = runCatching { ensure() }.isSuccess

    private fun load() {
        if (!privateFile.exists() || !publicFile.exists()) return
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
        keyPair = KeyPair(publicKey, privateKey)
    }

    private fun ensure(): KeyPair {
        keyPair?.let { return it }
        synchronized(this) {
            keyPair?.let { return it }
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            keyPair = pair
            runCatching {
                directory.mkdirs()
                privateFile.writeBytes(pair.private.encoded)
                publicFile.writeBytes(pair.public.encoded)
            }
            return pair
        }
    }

    /**
     * The base64 blob the device expects, in the same shape as `~/.android/adbkey.pub`:
     * the encoded key, a space and a human readable comment.
     */
    fun publicKeyPayload(): String {
        val blob = publicKeyBlob()
        return Base64.getEncoder().encodeToString(blob) + " " + COMMENT
    }

    /**
     * The fingerprint the phone shows in its "Allow USB debugging?" dialog.
     *
     * The platform hashes the DER encoded public key and prints it as pairs of
     * hex digits, so the app can show the same string and the user can tell
     * at a glance whether the prompt on the screen belongs to FrameScope.
     */
    fun publicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("MD5").digest(ensure().public.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /**
     * True when the phone was seen keeping this key in its own key store.
     *
     * A key that lives there is accepted silently, so the "Allow USB
     * debugging?" prompt never comes back - not after a reconnect and not
     * after a reboot.
     */
    val authorisationStored: Boolean get() = storedStamp.exists()

    /** Records that the phone keeps this key. */
    fun markAuthorisationStored() {
        runCatching {
            directory.mkdirs()
            storedStamp.writeText(publicKeyFingerprint())
        }
    }

    /** Forgets that the phone keeps this key. */
    fun clearAuthorisationStored() {
        runCatching { storedStamp.delete() }
    }

    /** The raw 524 byte Android public key blob. */
    fun publicKeyBlob(): ByteArray {
        val privateKey = ensure().private as RSAPrivateCrtKey
        val modulus = privateKey.modulus
        val exponent = privateKey.publicExponent
        require(modulus.bitLength() == 2048) { "Unexpected RSA modulus size: ${modulus.bitLength()}" }

        val out = ByteArray(ENCODED_SIZE)
        // Modulus size in 32 bit words.
        writeIntLe(out, 0, MODULUS_WORDS)
        // n0inv = -1 / n[0] mod 2^32.
        val low = modulus.mod(TWO_POW_32)
        val inverse = low.modInverse(TWO_POW_32)
        writeIntLe(out, 4, TWO_POW_32.subtract(inverse).mod(TWO_POW_32).toInt())
        // Modulus, little endian.
        toLittleEndian(modulus, MODULUS_SIZE).copyInto(out, 8)
        // rr = (2^2048)^2 mod n, little endian.
        val rr = BigInteger.ONE.shiftLeft(2 * MODULUS_SIZE * 8).mod(modulus)
        toLittleEndian(rr, MODULUS_SIZE).copyInto(out, 8 + MODULUS_SIZE)
        // Public exponent.
        writeIntLe(out, 8 + 2 * MODULUS_SIZE, exponent.toInt())
        return out
    }

    /**
     * Signs the authentication token the way `adbd` expects it: a plain
     * SHA1withRSA signature over the raw token bytes. The device verifies it
     * with `RSA_verify(NID_sha1, token, ...)` against the key it stores, so the
     * payload of the AUTH message must be the bare signature and nothing else.
     */
    fun sign(token: ByteArray): ByteArray? = runCatching {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(ensure().private)
        signature.update(token)
        signature.sign()
    }.getOrNull()

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val magnitude = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) {
            bigEndian.copyOfRange(1, bigEndian.size)
        } else {
            bigEndian
        }
        require(magnitude.size <= size) { "Value does not fit into $size bytes" }
        val padded = ByteArray(size)
        System.arraycopy(magnitude, 0, padded, size - magnitude.size, magnitude.size)
        return padded.reversedArray()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    companion object {
        private const val MODULUS_SIZE = 256
        private const val MODULUS_WORDS = 64
        private const val ENCODED_SIZE = 4 + 4 + MODULUS_SIZE + MODULUS_SIZE + 4
        const val COMMENT = "framescope@localhost"
        private val TWO_POW_32 = BigInteger.ONE.shiftLeft(32)
    }
}
