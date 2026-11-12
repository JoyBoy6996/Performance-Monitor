package com.framescope.monitor.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AccessState { READY, NEEDS_SETUP, UNAVAILABLE }

data class AccessStatus(
    val backendId: String = "none",
    val backendLabel: String = "No access",
    val state: AccessState = AccessState.NEEDS_SETUP,
    val detail: String = "",
    val checkedAt: Long = 0L
)

/**
 * Owns the available privilege backends and picks the best one that works.
 *
 * Order of preference: Shizuku -> local ADB bridge -> root -> basic.
 *
 * The hub also remembers why the last command failed, so the interface can
 * explain an empty dashboard instead of showing a row of dashes.
 */
class ShellHub(
    private val shizuku: ShellRunner = ShizukuRunner(),
    private val adb: ShellRunner,
    private val root: ShellRunner = RootRunner()
) {

    private val _status = MutableStateFlow(AccessStatus())
    val status: StateFlow<AccessStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Message of the most recent backend failure, if any. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var active: ShellRunner? = null

    @Volatile
    private var lastProbeAt = 0L

    /** The Shizuku backend, when that is the one in use. */
    val shizukuRunner: ShizukuRunner? get() = shizuku as? ShizukuRunner

    /** The ADB runner, when that is the backend in use. */
    val adbRunner: AdbTcpRunner? get() = adb as? AdbTcpRunner

    /**
     * How far the debugging authorisation on the phone has come.
     *
     * The interface uses it to tell the user whether the "Allow USB
     * debugging?" prompt is still going to come back.
     */
    val adbAuthState: StateFlow<AdbAuthState> =
        adbRunner?.authState ?: MutableStateFlow(AdbAuthState.UNKNOWN)

    /** The fingerprint the phone prints in its debugging prompt. */
    val adbFingerprint: String get() = adbRunner?.fingerprint() ?: ""

    /**
     * Re-checks every backend and refreshes [status].
     *
     * When the ADB bridge is the wanted backend but the phone has no listener
     * on the port - which is what a reboot leaves behind - the bridge is
     * switched back on with root or Shizuku. That is what makes the setup a
     * one-time thing: the app repairs the bridge by itself afterwards.
     */
    suspend fun refresh(
        preferred: String? = null,
        adbPort: Int = 5555,
        allowAdbBootstrap: Boolean = true
    ): AccessStatus = withContext(Dispatchers.IO) { refreshBlocking(preferred, adbPort, allowAdbBootstrap) }

    private suspend fun refreshBlocking(
        preferred: String?,
        adbPort: Int,
        allowAdbBootstrap: Boolean
    ): AccessStatus {
        lastProbeAt = System.currentTimeMillis()
        val mode = preferred?.lowercase()
        val candidates: List<ShellRunner> = when (mode) {
            "shizuku" -> listOf(shizuku)
            "adb" -> listOf(adb)
            "root" -> listOf(root)
            else -> listOf(shizuku, adb, root)
        }
        for (candidate in candidates) {
            var available = runCatching { candidate.isAvailable() }.getOrDefault(false)
            if (!available && candidate === adb && allowAdbBootstrap) {
                // No listener on the port. Root or Shizuku can switch it back
                // on, so the user never has to run the setup twice.
                available = ensureAdbBridge(adbPort)
            }
            if (available) {
                active = candidate
                _lastError.value = null
                _status.value = AccessStatus(
                    backendId = candidate.id,
                    backendLabel = candidate.label,
                    state = AccessState.READY,
                    detail = "",
                    checkedAt = System.currentTimeMillis()
                )
                return _status.value
            }
        }
        active = null
        val detail = when (mode) {
            "shizuku" -> shizuku.unavailableReason()
            "adb" -> adb.unavailableReason()
            "root" -> root.unavailableReason()
            else -> candidates.firstNotNullOfOrNull { runCatching { it.unavailableReason() }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: "No privileged backend available yet."
        }
        _status.value = AccessStatus(
            backendId = "none",
            backendLabel = "No access",
            state = AccessState.NEEDS_SETUP,
            detail = detail,
            checkedAt = System.currentTimeMillis()
        )
        return _status.value
    }

    /**
     * Looks for the ADB daemon the phone started itself and turns it into a
     * fixed bridge, so the app can be used without a computer and without
     * Wi-Fi. Returns a line for the interface.
     */
    suspend fun findBridgeOnPhone(port: Int): String = withContext(Dispatchers.IO) {
        findBridgeOnPhoneBlocking(port)
    }

    private suspend fun findBridgeOnPhoneBlocking(port: Int): String {
        val repairer = adb as? AdbBridgeRepairer
            ?: return "This build cannot repair the bridge."
        val discovered = runCatching { repairer.discoverLocalAdbPort() }.getOrNull()
            ?: return "No ADB daemon on this phone. Turn on Wireless debugging in " +
                "Developer options, then try again."
        val switched = runCatching { repairer.requestTcpPort(port, discovered) }.getOrDefault(false)
        if (!switched) {
            return "The ADB daemon on port $discovered refused to move to port $port."
        }
        delay(2_500)
        val working = runCatching { adb.isAvailable() }.getOrDefault(false)
        return if (working) {
            "The bridge is up on port $port. It works with Wi-Fi off and reconnects by itself."
        } else {
            "The bridge was moved to port $port but has not answered yet. Re-check in a moment."
        }
    }

    /** Points the ADB backend at another endpoint. */
    /**
     * A manual re-check.
     *
     * It clears the "the key was already offered" flag, so the phone can ask
     * for the debugging permission again after the prompt was missed.
     */
    suspend fun forceRecheck(preferred: String?, adbPort: Int, allowAdbBootstrap: Boolean) {
        (adb as? AdbTcpRunner)?.resetAuthorisation()
        refresh(preferred = preferred, adbPort = adbPort, allowAdbBootstrap = allowAdbBootstrap)
    }

    fun applyAdbEndpoint(newHost: String, newPort: Int) {
        (adb as? AdbTcpRunner)?.retarget(newHost, newPort)
    }

    /** Executes a command with the active backend, falling back to any working one. */
    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult =
        withContext(Dispatchers.IO) { execBlocking(command, timeoutMs) }

    private suspend fun execBlocking(command: String, timeoutMs: Long): ShellResult {
        val runner = active ?: throw ShellUnavailable(
            _lastError.value ?: "No privileged backend available"
        )
        return try {
            val result = runner.exec(command, timeoutMs)
            _lastError.value = null
            result
        } catch (e: Exception) {
            _lastError.value = e.message ?: "The command failed"
            // The active backend died - look for another one, but not on every single
            // failure, otherwise a broken bridge gets probed in a tight loop.
            if (System.currentTimeMillis() - lastProbeAt > PROBE_COOLDOWN_MS) {
                refresh()
                val fallback = active
                if (fallback != null && fallback !== runner) {
                    val retry = runCatching { fallback.exec(command, timeoutMs) }.getOrNull()
                    if (retry != null) {
                        _lastError.value = null
                        return retry
                    }
                }
            }
            throw e
        }
    }

    suspend fun execOrNull(command: String, timeoutMs: Long = 8_000): ShellResult? =
        withContext(Dispatchers.IO) {
            runCatching { execBlocking(command, timeoutMs) }.getOrNull()
        }

    /**
     * Brings the phone's own ADB bridge back up.
     *
     * `adb tcpip` only lasts until the next reboot, after which nothing is
     * listening on the port any more. The shell identity that `adb` uses can
     * set the property itself, so with root or Shizuku the app restarts the
     * daemon in TCP mode and the bridge is back.
     *
     * The bridge lives on the loopback interface, so it works with Wi-Fi off,
     * on mobile data, or on any network at all.
     */
    suspend fun ensureAdbBridge(port: Int): Boolean = withContext(Dispatchers.IO) {
        ensureAdbBridgeBlocking(port)
    }

    private suspend fun ensureAdbBridgeBlocking(port: Int): Boolean {
        if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true

        // The phone's own wireless debugging starts the ADB daemon on a random
        // port. Find it and ask it to move to a fixed port, which makes the
        // bridge work with no computer and no Wi-Fi involved.
        val repairer = adb as? AdbBridgeRepairer
        if (repairer != null) {
            val discovered = runCatching { repairer.discoverLocalAdbPort() }.getOrNull()
            if (discovered != null) {
                val switched = runCatching {
                    repairer.requestTcpPort(port, discovered)
                }.getOrDefault(false)
                if (switched) {
                    delay(2_500)
                    if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
                }
            }
        }

        val helper = listOf(root, shizuku).firstOrNull {
            runCatching { it.isAvailable() }.getOrDefault(false)
        } ?: return false
        runCatching {
            helper.exec(
                "setprop persist.adb.tcp.port $port; " +
                    "setprop service.adb.tcp.port $port; " +
                    "stop adbd; start adbd",
                8_000
            )
        }
        repeat(6) {
            delay(700)
            if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
        }
        return false
    }

    companion object {
        private const val PROBE_COOLDOWN_MS = 5_000L
    }
}
