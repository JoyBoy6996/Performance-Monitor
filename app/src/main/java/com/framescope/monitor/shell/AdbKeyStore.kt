package com.framescope.monitor.shell

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Host side of the ADB key exchange with persistent RSA key storage.
 *
 * `adbd` expects the 524-byte Android pubkey blob (little-endian integers, matching
 * AOSP libcrypto_utils/android_pubkey.cpp), base64 encoded.
 *
 * To avoid repeating the "Allow USB debugging?" prompt after every connection, app restart,
 * or reboot, the RSA key pair is generated exactly ONCE and preserved persistently in both
 * internal storage files and SharedPreferences.
 */
class AdbKeyStore(
    private val directory: File,
    private val context: Context? = null
) {

    private val privateFile = File(directory, "adb_host_key.pk8")
    private val publicFile = File(directory, "adb_host_key.pk8.pub")
    private val storedStamp = File(directory, "authorised.stamp")

    private val sp: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var keyPair: KeyPair? = null

    init {
        runCatching { load() }
    }

    /** True when a key pair is loaded or can be created on disk. */
    val hasKey: Boolean get() = runCatching { ensure() }.isSuccess

    private fun load() {
        // 1. Try loading from files first
        if (privateFile.exists() && publicFile.exists()) {
            runCatching {
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                keyPair = KeyPair(publicKey, privateKey)
                return
            }
        }

        // 2. Fallback to SharedPreferences if file was absent or corrupted
        val privBase64 = sp?.getString(KEY_PRIVATE, null)
        val pubBase64 = sp?.getString(KEY_PUBLIC, null)
        if (!privBase64.isNullOrBlank() && !pubBase64.isNullOrBlank()) {
            runCatching {
                val privBytes = Base64.getDecoder().decode(privBase64)
                val pubBytes = Base64.getDecoder().decode(pubBase64)
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(pubBytes))
                val pair = KeyPair(publicKey, privateKey)
                keyPair = pair
                // Restore files on disk
                runCatching {
                    directory.mkdirs()
                    privateFile.writeBytes(privBytes)
                    publicFile.writeBytes(pubBytes)
                }
            }
        }
    }

    fun ensure(): KeyPair {
        keyPair?.let { return it }
        synchronized(this) {
            keyPair?.let { return it }

            // Check load once more under lock
            load()
            keyPair?.let { return it }

            // Generate RSA 2048 key pair ONCE
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            keyPair = pair

            val privBytes = pair.private.encoded
            val pubBytes = pair.public.encoded

            // Persist to disk files
            runCatching {
                directory.mkdirs()
                privateFile.writeBytes(privBytes)
                publicFile.writeBytes(pubBytes)
            }

            // Persist to SharedPreferences backup
            runCatching {
                sp?.edit()
                    ?.putString(KEY_PRIVATE, Base64.getEncoder().encodeToString(privBytes))
                    ?.putString(KEY_PUBLIC, Base64.getEncoder().encodeToString(pubBytes))
                    ?.apply()
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
     */
    fun publicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("MD5").digest(ensure().public.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /**
     * True when the phone was seen keeping this key in its own key store.
     */
    val authorisationStored: Boolean
        get() = storedStamp.exists() || (sp?.getBoolean(KEY_AUTHORISED, false) == true)

    /** Records that the phone keeps this key. */
    fun markAuthorisationStored() {
        runCatching {
            directory.mkdirs()
            storedStamp.writeText(publicKeyFingerprint())
        }
        runCatching {
            sp?.edit()?.putBoolean(KEY_AUTHORISED, true)?.apply()
        }
    }

    /** Forgets that the phone keeps this key. */
    fun clearAuthorisationStored() {
        runCatching { storedStamp.delete() }
        runCatching {
            sp?.edit()?.putBoolean(KEY_AUTHORISED, false)?.apply()
        }
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
     * Signs the authentication token with SHA1withRSA.
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

        private const val PREFS_NAME = "framescope_adb_keystore"
        private const val KEY_PRIVATE = "rsa_private_pkcs8"
        private const val KEY_PUBLIC = "rsa_public_x509"
        private const val KEY_AUTHORISED = "is_authorised"
    }
}
