package com.framescope.monitor.shell

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small `adbd` for the tests.
 *
 * It follows the rules of the real one: the daemon challenges the host with a
 * token, the host answers with a signature, and a daemon that does not know
 * the key asks for it again. A daemon in [rejectKeyMode] never accepts
 * anything, so every attempt to offer the key is counted.
 *
 * A daemon that accepts the key answers a shell request with the text it was
 * given, framed the way `shell,v2,raw:` frames it.
 */
class MockAdbd(
    /** The daemon never accepts a key and never answers a command. */
    private val rejectKeyMode: Boolean = false,
    private val stdout: String = "",
    private val stderr: String = "",
    private val exitCode: Int = 0
) {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread { serve() }

    /** The port the daemon listens on. */
    val port: Int get() = server.localPort

    /** How often the host handed over its public key. */
    val publicKeyOfferCount = AtomicInteger(0)

    /** Every shell command the host sent. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        runCatching { server.close() }
    }

    private fun serve() {
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

            val hello = readMessage(input) ?: return
            if (hello.command != "CNXN") return

            var authorised = false
            var tokens = 0
            while (!authorised) {
                if (tokens > 4) return
                tokens++
                send(output, "AUTH", AUTH_TOKEN, 0, ByteArray(20) { (it + tokens).toByte() })
                val reply = readMessage(input) ?: return
                when {
                    reply.command == "AUTH" && reply.arg0 == AUTH_SIGNATURE -> {
                        // The signature is not checked here: the test is about
                        // the dialogue, not about the cryptography.
                        if (rejectKeyMode) {
                            // Ask again. The host has to offer its key.
                            continue
                        }
                        authorised = true
                        send(
                            output,
                            "CNXN",
                            0x01000001,
                            256 * 1024,
                            "device::ro.product.name=test:features=shell_v2,cmd".toByteArray() + 0
                        )
                    }
                    reply.command == "AUTH" && reply.arg0 == AUTH_PUBLIC_KEY -> {
                        publicKeyOfferCount.incrementAndGet()
                        // Ask once more, so the host gives up.
                        continue
                    }
                    else -> return
                }
            }

            while (true) {
                val msg = readMessage(input) ?: return
                if (msg.command != "OPEN") {
                    if (msg.command == "CLSE") continue
                    return
                }
                val service = String(msg.data).trim { it <= ' ' }
                commands.add(service)
                send(output, "OKAY", 1, msg.arg0, ByteArray(0))
                if (!service.startsWith("shell")) {
                    send(output, "CLSE", 1, msg.arg0, ByteArray(0))
                    continue
                }
                val command = service.substringAfter("raw:", "").trim()
                val answer = when {
                    // The runner probes the bridge with `echo <mark>`; the
                    // daemon has to answer with the mark, or the probe calls
                    // the bridge dead.
                    command.startsWith("echo ") -> command.removePrefix("echo ").trim() + "\n"
                    else -> stdout
                }
                val payload = frame(SHELL_ID_STDOUT, answer.toByteArray()) +
                    frame(SHELL_ID_STDERR, stderr.toByteArray()) +
                    frame(SHELL_ID_EXIT, byteArrayOf(exitCode.toByte()))
                // Deliberately split over two packets, so the client has to
                // put them back together.
                val cut = payload.size / 2
                send(output, "WRTE", 1, msg.arg0, payload.copyOfRange(0, cut))
                send(output, "WRTE", 1, msg.arg0, payload.copyOfRange(cut, payload.size))
                send(output, "CLSE", 1, msg.arg0, ByteArray(0))
            }
        } catch (_: Exception) {
            // The test closes the socket on purpose.
        }
    }

    private fun frame(id: Int, data: ByteArray): ByteArray {
        val out = ByteArray(5 + data.size)
        out[0] = id.toByte()
        val len = data.size
        out[1] = (len and 0xFF).toByte()
        out[2] = ((len shr 8) and 0xFF).toByte()
        out[3] = ((len shr 16) and 0xFF).toByte()
        out[4] = ((len shr 24) and 0xFF).toByte()
        data.copyInto(out, 5)
        return out
    }

    private fun send(output: OutputStream, command: String, arg0: Int, arg1: Int, data: ByteArray) {
        val cmd = commandToMagic(command)
        val header = ByteArray(24)
        putInt(header, 0, cmd)
        putInt(header, 4, arg0)
        putInt(header, 8, arg1)
        putInt(header, 12, data.size)
        putInt(header, 16, crc32(data))
        putInt(header, 20, cmd xor -1)
        synchronized(output) {
            output.write(header)
            if (data.isNotEmpty()) output.write(data)
            output.flush()
        }
    }

    private fun readMessage(input: InputStream): Message? {
        val header = ByteArray(24)
        if (!readFully(input, header)) return null
        val command = String(header, 0, 4, Charsets.US_ASCII)
        val arg0 = getInt(header, 4)
        val arg1 = getInt(header, 8)
        val length = getInt(header, 12)
        val magic = getInt(header, 20)
        if (magic != (commandToMagic(command) xor -1)) return null
        val data = ByteArray(maxOf(length, 0))
        if (data.isNotEmpty() && !readFully(input, data)) return null
        return Message(command, arg0, arg1, data)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private data class Message(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_PUBLIC_KEY = 3
        private const val SHELL_ID_STDOUT = 1
        private const val SHELL_ID_STDERR = 2
        private const val SHELL_ID_EXIT = 3

        private fun commandToMagic(command: String): Int =
            (command[0].code and 0xFF) or
                ((command[1].code and 0xFF) shl 8) or
                ((command[2].code and 0xFF) shl 16) or
                ((command[3].code and 0xFF) shl 24)

        private fun putInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value shr 8) and 0xFF).toByte()
            target[offset + 2] = ((value shr 16) and 0xFF).toByte()
            target[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun getInt(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xFF) or
                ((source[offset + 1].toInt() and 0xFF) shl 8) or
                ((source[offset + 2].toInt() and 0xFF) shl 16) or
                ((source[offset + 3].toInt() and 0xFF) shl 24)
    }
}
