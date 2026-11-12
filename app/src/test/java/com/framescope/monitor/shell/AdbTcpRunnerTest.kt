package com.framescope.monitor.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Verifies the ADB client against a mock `adbd` that follows the same rules
 * the real one does: it challenges the host with a token, checks the RSA
 * signature against the Android public key blob, and streams the shell output
 * with the shell v2 framing.
 */
class AdbTcpRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun findsTheAdbDaemonThatThePhoneStartedItself() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        // A port inside the range the wireless debugging service uses.
        val server = MockAdbd(
            knownKey = keys.publicKeyBlob(),
            fixedPort = 39_117
        )
        server.start()
        val found = runner(keys, 39_117).discoverLocalAdbPort()
        assertEquals(39_117, found)
        server.stop()
    }

    @Test
    fun theDiscoveryScanDoesNotOfferThePublicKey() = runBlocking {
        // The daemon does not know the key. The scan must still find the port,
        // but it must not hand the key over: the phone raises an
        // "Allow USB debugging?" prompt for every key it has not seen, so a
        // scan that offers the key stacks up prompts.
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(knownKey = null, fixedPort = 39_117)
        server.start()
        val found = runner(keys, 39_117).discoverLocalAdbPort()
        assertEquals(39_117, found)
        assertFalse("the scan must not offer the key", server.receivedPublicKey)
        server.stop()
    }

    @Test
    fun theKeyIsNotOfferedAgainAfterItWasHandedOver() = runBlocking {
        // The phone does not answer the prompt, so the key stays unknown. The
        // next attempt must not hand it over again: every key the phone has not
        // seen raises another "Allow USB debugging?" prompt over the first one.
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(knownKey = null, rejectKey = true)
        server.start()
        val runner = runner(keys, server.port)
        runCatching { runner.exec("echo ok", 3_000) }
        // Long enough for the runner to try again instead of waiting out its
        // own short backoff.
        Thread.sleep(4_500)
        runCatching { runner.exec("echo ok", 3_000) }
        assertEquals(
            "the key must be offered once, not once per attempt",
            1,
            server.publicKeyOfferCount
        )
        server.stop()
    }

    @Test
    fun findsNothingWhenThePhoneHasNoAdbDaemon() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        // No daemon is running on this machine, so the scan has to come back
        // empty instead of hanging or throwing.
        val found = runner(keys, 39_117).discoverLocalAdbPort()
        assertNull(found)
    }

    @Test
    fun asksTheDaemonToMoveToAFixedPort() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(knownKey = keys.publicKeyBlob())
        server.start()
        val accepted = runner(keys, server.port).requestTcpPort(5555, server.port)
        assertTrue("the daemon should have accepted the request", accepted)
        assertEquals("tcpip:5555", server.lastService)
        server.stop()
    }

    @Test
    fun runsACommandThroughAnAuthenticatedSession() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        // The device already knows this key, so the signature is enough.
        val server = MockAdbd(knownKey = keys.publicKeyBlob(), stdout = "hello\n")
        server.start()
        val result = runner(keys, server.port).exec("echo hello", 5_000)
        assertEquals("hello\n", result.out)
        assertEquals(0, result.code)
        assertTrue(
            "the command should have been sent",
            server.services.contains("shell,v2,raw:echo hello")
        )
        assertFalse("the key should not have to be offered again", server.receivedPublicKey)
        server.stop()
    }

    @Test
    fun theKeyIsWrittenIntoThePhoneKeyStoreAfterThePrompt() = runBlocking {
        // The phone did not know the key, so it was handed over. The app then
        // puts the key into the phone's own key store, which is what makes the
        // "Allow USB debugging?" prompt a one-time thing.
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(
            knownKey = null,
            stdout = "ok\n",
            onCommand = { service ->
                if (service.contains("adb_keys")) "__framescope_keys_ok__" else "ok\n"
            }
        )
        server.start()
        try {
            val runner = runner(keys, server.port)
            runner.exec("echo ok", 5_000)
            assertTrue("the key should have been stored on the phone", keys.authorisationStored)
            assertEquals(AdbAuthState.STORED, runner.authState.value)
            assertTrue(
                "the key store should have been written",
                server.services.any { it.contains("/data/misc/adb/adb_keys") }
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun theKeyIsNotWrittenIntoThePhoneKeyStoreWhenThePhoneRefuses() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(
            knownKey = null,
            stdout = "ok\n",
            onCommand = { service ->
                if (service.contains("adb_keys")) "__framescope_keys_failed__" else "ok\n"
            }
        )
        server.start()
        try {
            val runner = runner(keys, server.port)
            runner.exec("echo ok", 5_000)
            assertFalse("the key must not be marked as stored", keys.authorisationStored)
            assertEquals(AdbAuthState.PROMPT_SENT, runner.authState.value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun theKeyIsOfferedAgainAfterAManualRecheck() = runBlocking {
        // "Re-check" is the explicit way to ask the phone for a new prompt,
        // so it has to clear the "already offered" flag.
        val keys = AdbKeyStore(temp.newFolder())
        // The daemon knows a different key, so it can never accept ours and
        // the client has to offer it on every attempt.
        val server = MockAdbd(
            knownKey = AdbKeyStore(temp.newFolder()).publicKeyBlob(),
            rejectKey = true
        )
        server.start()
        try {
            val runner = runner(keys, server.port)
            runCatching { runner.exec("echo ok", 3_000) }
            assertEquals(1, server.publicKeyOfferCount)
            runner.resetAuthorisation()
            runCatching { runner.exec("echo ok", 3_000) }
            assertEquals(
                "a manual re-check has to be able to raise a new prompt",
                2,
                server.publicKeyOfferCount
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun theFingerprintMatchesTheFormatThePhonePrints() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        val fingerprint = keys.publicKeyFingerprint()
        assertEquals("the fingerprint is 16 bytes", 16, fingerprint.split(":").size)
        assertTrue(
            "the fingerprint is upper case hex",
            fingerprint.all { it == ':' || it in "0123456789ABCDEF" }
        )
        // The same key must always print the same fingerprint, otherwise the
        // phone would ask for permission again.
        assertEquals(fingerprint, keys.publicKeyFingerprint())
    }

    @Test
    fun reportsTheExitCodeAndSeparatesStderr() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(knownKey = keys.publicKeyBlob(), stdout = "out\n", stderr = "boom\n", exitCode = 3)
        server.start()
        val result = runner(keys, server.port).exec("failing", 5_000)
        assertEquals("out\n", result.out)
        assertEquals("boom\n", result.err)
        assertEquals(3, result.code)
        server.stop()
    }

    @Test
    fun offersThePublicKeyWhenTheDeviceDoesNotKnowIt() = runBlocking {
        // The device has never seen this key: the client has to hand over its
        // public key so the "Allow USB debugging?" prompt can appear.
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(knownKey = null, stdout = "ok\n")
        server.start()
        val result = runner(keys, server.port).exec("echo ok", 5_000)
        assertEquals("ok\n", result.out)
        assertTrue("the device never received a public key", server.receivedPublicKey)
        val blob = server.publicKeyBlob
        assertEquals(524, blob?.size)
        // AOSP's Android pubkey blob starts with the modulus size in words (64).
        assertEquals(64, blob?.let {
            (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or
                ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
        })
        server.stop()
    }

    @Test
    fun worksWithoutAuthentication() = runBlocking {
        val server = MockAdbd(requireAuth = false, stdout = "plain\n")
        server.start()
        val result = runner(AdbKeyStore(temp.newFolder()), server.port).exec("echo plain", 5_000)
        assertEquals("plain\n", result.out)
        server.stop()
    }

    @Test
    fun fallsBackToPlainStreamWhenTheDeviceIgnoresShellV2() = runBlocking {
        val keys = AdbKeyStore(temp.newFolder())
        val server = MockAdbd(
            knownKey = keys.publicKeyBlob(),
            shellV2 = false,
            stdout = "legacy\n",
            deviceBanner = "device::ro.product.name=test"
        )
        server.start()
        val result = runner(keys, server.port).exec("echo legacy", 5_000)
        assertEquals("legacy\n", result.out)
        server.stop()
    }

    @Test
    fun reportsAnUnreachableBridgeInsteadOfPretendingToWork() = runBlocking {
        val runner = AdbTcpRunner(host = "127.0.0.1", port = 1, keys = AdbKeyStore(temp.newFolder()))
        assertFalse(runner.isAvailable())
        assertTrue(runner.unavailableReason().isNotBlank())
    }

    private fun runner(keys: AdbKeyStore, port: Int) =
        AdbTcpRunner(host = "127.0.0.1", port = port, keys = keys)

    /**
     * A very small `adbd`: enough of the protocol to challenge the host,
     * verify its signature with the key the host offers and answer a shell
     * request.
     */
    private class MockAdbd(
        private val knownKey: ByteArray? = null,
        private val requireAuth: Boolean = true,
        private val rejectKey: Boolean = false,
        private val shellV2: Boolean = true,
        private val stdout: String = "",
        private val stderr: String = "",
        private val exitCode: Int = 0,
        private val deviceBanner: String = "device::ro.product.name=test:features=shell_v2,cmd",
        private val onCommand: ((String) -> String?)? = null,
        fixedPort: Int = 0
    ) {
        private val server = ServerSocket(fixedPort, 4, InetAddress.getByName("127.0.0.1"))
        private val thread = Thread { serve() }

        val port: Int get() = server.localPort

        @Volatile var lastService: String = ""
        @Volatile var receivedPublicKey: Boolean = false
        @Volatile var publicKeyOfferCount: Int = 0
        @Volatile var publicKeyBlob: ByteArray? = null

        /** Every service the host opened, in order. */
        val services: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            runCatching { server.close() }
        }

        private fun serve() {
            // The discovery scan connects first to see whether the port is
            // open, so the mock has to accept more than one connection.
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    return
                }
                Thread { handle(socket) }.apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun handle(socket: Socket) {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // The host connects first.
                val cnxn = readMessage(input) ?: return
                if (cnxn.command != "CNXN") return

                var authorized = !requireAuth
                var rejected = false
                while (!authorized) {
                    val token = Random.nextBytes(20)
                    send(output, "AUTH", 1, 0, token)
                    val reply = readMessage(input) ?: return
                    when {
                        reply.command == "AUTH" && reply.arg0 == 2 -> {
                            if (!rejected && verify(token, reply.data, knownKey ?: publicKeyBlob)) {
                                authorized = true
                            } else {
                                // The device does not know the key yet.
                                rejected = true
                            }
                        }
                        reply.command == "AUTH" && reply.arg0 == 3 -> {
                            receivedPublicKey = true
                            publicKeyOfferCount++
                            publicKeyBlob = decodePublicKey(reply.data)
                            authorized = !rejectKey
                        }
                        else -> return
                    }
                }

                send(output, "CNXN", 0x01000001, 256 * 1024, deviceBanner.toByteArray() + 0)

                while (true) {
                    val msg = readMessage(input) ?: return
                    when (msg.command) {
                        "OPEN" -> {
                            lastService = String(msg.data).trim { it <= ' ' }
                            services.add(lastService)
                            send(output, "OKAY", 1, msg.arg0, ByteArray(0))
                            val payload = buildPayload(lastService)
                            // Deliberately split the stream over two WRTE messages so the
                            // client has to reassemble the shell v2 packets.
                            val cut = payload.size / 2
                            send(output, "WRTE", 1, msg.arg0, payload.copyOfRange(0, cut))
                            send(output, "WRTE", 1, msg.arg0, payload.copyOfRange(cut, payload.size))
                            send(output, "CLSE", 1, msg.arg0, ByteArray(0))
                        }
                        "CLSE" -> return
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                // The test tears the socket down at the end.
            }
        }

        private fun buildPayload(service: String): ByteArray {
            val text = onCommand?.invoke(service) ?: stdout
            if (!shellV2) return text.toByteArray()
            val out = ByteArrayOutputStream()
            out.writePacket(1, text.toByteArray())
            if (stderr.isNotEmpty()) out.writePacket(2, stderr.toByteArray())
            out.writePacket(3, byteArrayOf(exitCode.toByte()))
            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.writePacket(id: Int, payload: ByteArray) {
            write(id)
            write(payload.size and 0xFF)
            write((payload.size shr 8) and 0xFF)
            write((payload.size shr 16) and 0xFF)
            write((payload.size shr 24) and 0xFF)
            write(payload)
        }

        /** Verifies the host's signature exactly like `adbd` does. */
        private fun verify(token: ByteArray, signature: ByteArray, blob: ByteArray?): Boolean = runCatching {
            val key = blob ?: return false
            if (key.size != 524) return false
            val modulus = key.copyOfRange(8, 264).reversedArray().toUnsignedBigInteger()
            // The exponent is a native little endian uint32 in the blob.
            val exponentBytes = key.copyOfRange(520, 524)
            val exponent = byteArrayOf(
                exponentBytes[3], exponentBytes[2], exponentBytes[1], exponentBytes[0]
            ).toUnsignedBigInteger()
            val recovered = signature.toUnsignedBigInteger().modPow(exponent, modulus).toByteArray()
            // The plain text block is 256 bytes wide and starts with 00 01.
            val plain = recovered.toUnsignedBigInteger().toByteArray()
            val block = ByteArray(256)
            plain.copyInto(block, 256 - plain.size)
            // PKCS#1 v1.5: 00 01 FF..FF 00 || DigestInfo(SHA1) || digest
            if (block.size < 11 + 15 + 20) return false
            if (block[0].toInt() != 0 || block[1].toInt() != 1) return false
            var i = 2
            while (i < block.size && (block[i].toInt() and 0xFF) == 0xFF) i++
            if (i >= block.size || block[i].toInt() != 0) return false
            i++
            val digestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
            )
            for (k in digestInfo.indices) {
                if (block[i + k] != digestInfo[k]) return false
            }
            val signed = block.copyOfRange(i + digestInfo.size, block.size)
            MessageDigest.getInstance("SHA-1").digest(token).contentEquals(signed)
        }.getOrDefault(false)

        /** Decodes the "<base64> <comment>" payload the host sends. */
        private fun decodePublicKey(payload: ByteArray): ByteArray? = runCatching {
            val text = String(payload).trim { it <= ' ' }
            Base64.getDecoder().decode(text.substringBefore(' '))
        }.getOrNull()

        private fun send(output: OutputStream, command: String, arg0: Int, arg1: Int, data: ByteArray) {
            val header = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put(command.toByteArray(Charsets.US_ASCII))
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(data.size)
            header.putInt(crc32(data))
            header.putInt(magic(command))
            output.write(header.array())
            output.write(data)
            output.flush()
        }

        private fun readMessage(input: InputStream): Msg? {
            val header = ByteArray(24)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) return null
                read += n
            }
            val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val command = String(header, 0, 4, Charsets.US_ASCII)
            val arg0 = bb.getInt(4)
            val arg1 = bb.getInt(8)
            val length = bb.getInt(12)
            val data = ByteArray(maxOf(length, 0))
            read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n < 0) return null
                read += n
            }
            return Msg(command, arg0, arg1, data)
        }

        private fun magic(command: String): Int {
            var value = 0
            for (i in command.indices.reversed()) value = (value shl 8) or (command[i].code and 0xFF)
            return value xor 0xFFFFFFFF.toInt()
        }

        private fun crc32(data: ByteArray): Int {
            val crc = java.util.zip.CRC32()
            crc.update(data)
            return crc.value.toInt()
        }

        private data class Msg(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray)
    }
}

/**
 * Android's BigInteger has no (signum, magnitude) constructor and reads a byte
 * array as two's complement, so an unsigned value needs a leading zero byte.
 */
private fun ByteArray.toUnsignedBigInteger(): BigInteger {
    if (isNotEmpty() && (this[0].toInt() and 0x80) != 0) {
        val positive = ByteArray(size + 1)
        System.arraycopy(this, 0, positive, 1, size)
        return BigInteger(positive)
    }
    return BigInteger(this)
}
