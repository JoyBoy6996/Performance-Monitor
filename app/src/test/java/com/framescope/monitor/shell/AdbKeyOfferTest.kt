package com.framescope.monitor.shell

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The "Allow USB debugging?" dialog must not come back on its own.
 *
 * The phone shows the dialog for every key it has not seen. Handing the key
 * over again - after a restart of the app, after a reboot, after a failed
 * setup - stacks up a new dialog over the old one, and the app looks broken.
 *
 * The key is therefore only ever handed over once, and the fact that it was
 * handed over is written to the app's own storage.
 */
class AdbKeyOfferTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the key offer is remembered on disk`() {
        val folder = temp.newFolder()
        val keys = AdbKeyStore(folder)
        assertFalse("a key that was never handed over is not remembered", keys.keyWasOffered)

        keys.markKeyOffered()

        // A new key store over the same folder stands for the app after it
        // was restarted.
        val afterRestart = AdbKeyStore(folder)
        assertTrue(
            "the offer has to survive a restart of the app",
            afterRestart.keyWasOffered
        )

        // Only an explicit action may forget it.
        afterRestart.clearKeyOffered()
        assertFalse(AdbKeyStore(folder).keyWasOffered)
    }

    @Test
    fun `the key is not offered again after the app was restarted`() = runBlocking {
        val folder = temp.newFolder()
        val keys = AdbKeyStore(folder)
        // The daemon never accepts the key, so every offer would be a new
        // dialog on the phone.
        val daemon = MockAdbd(rejectKeyMode = true)
        daemon.start()
        try {
            val first = AdbTcpRunner(host = "127.0.0.1", port = daemon.port, keys = keys)
            runCatching { first.exec("echo ok", 3_000) }
            assertEquals(
                "the key has to be offered once so the dialog can appear",
                1,
                daemon.publicKeyOfferCount.get()
            )

            // The app is restarted: a new runner over the same storage.
            val restarted = AdbTcpRunner(host = "127.0.0.1", port = daemon.port, keys = AdbKeyStore(folder))
            runCatching { restarted.exec("echo ok", 3_000) }
            assertEquals(
                "the key must not be offered a second time after a restart",
                1,
                daemon.publicKeyOfferCount.get()
            )
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a manual re-check may offer the key again`() = runBlocking {
        val folder = temp.newFolder()
        val keys = AdbKeyStore(folder)
        val daemon = MockAdbd(rejectKeyMode = true)
        daemon.start()
        try {
            val runner = AdbTcpRunner(host = "127.0.0.1", port = daemon.port, keys = keys)
            runCatching { runner.exec("echo ok", 3_000) }
            assertEquals(1, daemon.publicKeyOfferCount.get())

            // The user asked for a new dialog on purpose.
            runner.resetAuthorisation()
            assertFalse(
                "an explicit re-check has to forget that the key was offered",
                AdbKeyStore(folder).keyWasOffered
            )

            runCatching { runner.exec("echo ok", 3_000) }
            assertEquals(
                "an explicit re-check has to be able to raise a new dialog",
                2,
                daemon.publicKeyOfferCount.get()
            )
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `the key store keeps its key across restarts`() {
        val folder = temp.newFolder()
        val keys = AdbKeyStore(folder)
        val fingerprint = keys.publicKeyFingerprint()

        val afterRestart = AdbKeyStore(folder)
        assertEquals(
            "the same key has to be used after a restart, or the phone asks again",
            fingerprint,
            afterRestart.publicKeyFingerprint()
        )
        assertTrue(File(folder, "adb_host_key.pk8").exists())
    }
}
