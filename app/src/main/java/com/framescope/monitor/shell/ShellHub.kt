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
 * When Shizuku is installed and running, it is always prioritized over local ADB
 * to avoid unnecessary ADB prompts.
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

    @Volatile
    private var consecutiveFailures = 0

    /** The Shizuku backend, when that is the one in use. */
    val shizukuRunner: ShizukuRunner? get() = shizuku as? ShizukuRunner

    /** The ADB runner, when that is the backend in use. */
    val adbRunner: AdbTcpRunner? get() = adb as? AdbTcpRunner

    /**
     * How far the debugging authorisation on the phone has come.
     */
    val adbAuthState: StateFlow<AdbAuthState> =
        adbRunner?.authState ?: MutableStateFlow(AdbAuthState.UNKNOWN)

    /** The fingerprint the phone prints in its debugging prompt. */
    val adbFingerprint: String get() = adbRunner?.fingerprint() ?: ""

    /**
     * Re-checks every backend and refreshes [status].
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

        // Order: Shizuku is always prioritized first in AUTO mode
        val candidates: List<ShellRunner> = when (mode) {
            "shizuku" -> listOf(shizuku)
            "adb" -> listOf(adb)
            "root" -> listOf(root)
            else -> listOf(shizuku, adb, root)
        }

        for (candidate in candidates) {
            var available = runCatching { candidate.isAvailable() }.getOrDefault(false)
            if (!available && candidate === adb && allowAdbBootstrap) {
                // If ADB is requested and not available, try to repair or bootstrap
                available = ensureAdbBridge(adbPort)
            }
            if (available) {
                active = candidate
                _lastError.value = null
                consecutiveFailures = 0
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
     * fixed bridge.
     */
    suspend fun findBridgeOnPhone(port: Int): String = withContext(Dispatchers.IO) {
        findBridgeOnPhoneBlocking(port)
    }

    private suspend fun findBridgeOnPhoneBlocking(port: Int): String {
        val repairer = adb as? AdbBridgeRepairer
            ?: return "This build cannot repair the bridge."
        val discovered = runCatching { repairer.discoverLocalAdbPort() }.getOrNull()
            ?: return "No ADB daemon on this phone. Turn on Wireless debugging in Developer options, then try again."
        val switched = runCatching { repairer.requestTcpPort(port, discovered) }.getOrDefault(false)
        if (!switched) {
            return "The ADB daemon on port $discovered refused to move to port $port."
        }
        delay(2_000)
        val working = runCatching { adb.isAvailable() }.getOrDefault(false)
        return if (working) {
            "The bridge is up on port $port. It reconnects automatically."
        } else {
            "The bridge was moved to port $port but has not answered yet. Re-check in a moment."
        }
    }

    suspend fun forceRecheck(preferred: String?, adbPort: Int, allowAdbBootstrap: Boolean) {
        (adb as? AdbTcpRunner)?.resetAuthorisation()
        consecutiveFailures = 0
        refresh(preferred = preferred, adbPort = adbPort, allowAdbBootstrap = allowAdbBootstrap)
    }

    fun applyAdbEndpoint(newHost: String, newPort: Int) {
        (adb as? AdbTcpRunner)?.retarget(newHost, newPort)
    }

    /** Executes a command with the active backend, falling back to any working one. */
    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult =
        withContext(Dispatchers.IO) { execBlocking(command, timeoutMs) }

    private suspend fun execBlocking(command: String, timeoutMs: Long): ShellResult {
        val runner = active ?: run {
            // Lazy refresh if no runner is currently selected
            val status = refreshBlocking(null, 5555, false)
            active ?: throw ShellUnavailable(status.detail.ifBlank { "No privileged backend available" })
        }

        return try {
            val result = runner.exec(command, timeoutMs)
            _lastError.value = null
            consecutiveFailures = 0
            result
        } catch (e: Exception) {
            _lastError.value = e.message ?: "The command failed"
            consecutiveFailures++

            // Exponential backoff cooldown before probing again
            val backoffMs = (BASE_COOLDOWN_MS * (1 shl minOf(consecutiveFailures - 1, 4))).coerceIn(BASE_COOLDOWN_MS, MAX_COOLDOWN_MS)
            if (System.currentTimeMillis() - lastProbeAt > backoffMs) {
                refresh()
                val fallback = active
                if (fallback != null && fallback !== runner) {
                    val retry = runCatching { fallback.exec(command, timeoutMs) }.getOrNull()
                    if (retry != null) {
                        _lastError.value = null
                        consecutiveFailures = 0
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

    suspend fun ensureAdbBridge(port: Int): Boolean = withContext(Dispatchers.IO) {
        ensureAdbBridgeBlocking(port)
    }

    private suspend fun ensureAdbBridgeBlocking(port: Int): Boolean {
        if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true

        val repairer = adb as? AdbBridgeRepairer
        if (repairer != null) {
            val discovered = runCatching { repairer.discoverLocalAdbPort() }.getOrNull()
            if (discovered != null) {
                val switched = runCatching {
                    repairer.requestTcpPort(port, discovered)
                }.getOrDefault(false)
                if (switched) {
                    delay(2_000)
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
        repeat(5) {
            delay(800)
            if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
        }
        return false
    }

    companion object {
        private const val BASE_COOLDOWN_MS = 3_000L
        private const val MAX_COOLDOWN_MS = 30_000L
    }
}
