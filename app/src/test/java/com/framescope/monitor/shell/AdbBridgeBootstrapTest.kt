package com.framescope.monitor.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADB bridge has to come back on its own.
 *
 * `adb tcpip` only lasts until the next reboot, and the user should never have
 * to run it again. With root or Shizuku the app restarts the phone's daemon in
 * TCP mode by itself.
 */
class AdbBridgeBootstrapTest {

    /** The state of the phone's ADB bridge, shared by the fake runners. */
    private class Bridge {
        var up: Boolean = false
    }

    /** A runner that records the commands it was asked to run. */
    private class FakeRunner(
        override val id: String,
        override val label: String,
        private val available: () -> Boolean,
        private val bridge: Bridge,
        val log: MutableList<String> = mutableListOf()
    ) : ShellRunner {
        override suspend fun isAvailable(): Boolean = available()
        override suspend fun unavailableReason(): String =
            "No ADB bridge. Run `adb tcpip 5555` once from a PC, then retry." 
        override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
            log += command
            // The daemon comes back up as soon as it has been told the port.
            if (command.contains("setprop service.adb.tcp.port")) bridge.up = true
            return ShellResult(0, "")
        }
    }

    @Test
    fun switchesTheBridgeBackOnWhenOnlyRootIsAvailable() = runTest {
        val bridge = Bridge()
        val adb = FakeRunner("adb", "ADB", { bridge.up }, bridge)
        val root = FakeRunner("root", "Root", { true }, bridge)
        val shizuku = FakeRunner("shizuku", "Shizuku", { false }, bridge)
        val hub = ShellHub(shizuku = shizuku, adb = adb, root = root)

        val status = hub.refresh(preferred = "adb", adbPort = 5555, allowAdbBootstrap = true)

        assertEquals(AccessState.READY, status.state)
        assertEquals("adb", status.backendId)
        val bootstrap = root.log.firstOrNull { it.contains("setprop") }
        assertTrue("the bridge should have been switched back on", bootstrap != null)
        assertTrue(bootstrap!!.contains("5555"))
        assertTrue(bootstrap.contains("start adbd"))
    }

    @Test
    fun usesShizukuWhenRootIsNotThere() = runTest {
        val bridge = Bridge()
        val adb = FakeRunner("adb", "ADB", { bridge.up }, bridge)
        val root = FakeRunner("root", "Root", { false }, bridge)
        val shizuku = FakeRunner("shizuku", "Shizuku", { true }, bridge)
        val hub = ShellHub(shizuku = shizuku, adb = adb, root = root)

        val status = hub.refresh(preferred = "adb", adbPort = 5555, allowAdbBootstrap = true)

        assertEquals(AccessState.READY, status.state)
        assertTrue(shizuku.log.any { it.contains("setprop") })
        assertTrue(root.log.isEmpty())
    }

    @Test
    fun leavesTheBridgeAloneWhenTheRepairIsTurnedOff() = runTest {
        val bridge = Bridge()
        val adb = FakeRunner("adb", "ADB", { bridge.up }, bridge)
        val root = FakeRunner("root", "Root", { true }, bridge)
        val shizuku = FakeRunner("shizuku", "Shizuku", { false }, bridge)
        val hub = ShellHub(shizuku = shizuku, adb = adb, root = root)

        val status = hub.refresh(preferred = "adb", adbPort = 5555, allowAdbBootstrap = false)

        assertEquals(AccessState.NEEDS_SETUP, status.state)
        assertTrue("nothing should have been run", root.log.isEmpty())
        assertTrue(status.detail.contains("adb tcpip"))
    }

    @Test
    fun doesNotTouchAnythingWhenTheBridgeIsAlreadyUp() = runTest {
        val bridge = Bridge()
        bridge.up = true
        val adb = FakeRunner("adb", "ADB", { bridge.up }, bridge)
        val root = FakeRunner("root", "Root", { true }, bridge)
        val shizuku = FakeRunner("shizuku", "Shizuku", { false }, bridge)
        val hub = ShellHub(shizuku = shizuku, adb = adb, root = root)

        val status = hub.refresh(preferred = "adb", adbPort = 5555, allowAdbBootstrap = true)

        assertEquals(AccessState.READY, status.state)
        assertTrue("a working bridge must not be restarted", root.log.isEmpty())
    }
}
