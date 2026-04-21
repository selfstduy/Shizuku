package moe.shizuku.manager.adb

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ktx.workerHandler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RequiresApi(Build.VERSION_CODES.R)
class HeadlessAdbCommandHandler(context: Context) {

    companion object {
        const val METHOD_ADB_PAIR = "adbPair"
        const val METHOD_ADB_START = "adbStart"
        const val METHOD_ADB_PAIR_AND_START = "adbPairAndStart"
        const val METHOD_ADB_PAIRING_NOTIFY_START = "adbPairingNotifyStart"
        const val METHOD_ADB_PAIRING_NOTIFY_STOP = "adbPairingNotifyStop"

        const val EXTRA_OK = "ok"
        const val EXTRA_ERROR = "error"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LOG = "log"
        const val EXTRA_HOST = "host"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_CONNECT_PORT = "connect_port"
        const val EXTRA_DISCOVERY_TIMEOUT = "discovery_timeout_ms"
        const val EXTRA_START_TIMEOUT = "start_timeout_ms"
        const val EXTRA_WAIT_FOR_SERVER = "wait_for_server"
        const val EXTRA_ENABLE_TCPIP_5555 = "enable_tcpip_5555"

        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_DISCOVERY_TIMEOUT = 15_000L
        private const val DEFAULT_START_TIMEOUT = 10_000L
        private const val DEFAULT_ENABLE_TCPIP_5555 = false
    }

    private val appContext = context.applicationContext

    init {
        ShizukuSettings.initialize(appContext)
    }

    fun canHandle(method: String): Boolean {
        return method == METHOD_ADB_PAIR
            || method == METHOD_ADB_START
            || method == METHOD_ADB_PAIR_AND_START
            || method == METHOD_ADB_PAIRING_NOTIFY_START
            || method == METHOD_ADB_PAIRING_NOTIFY_STOP
    }

    fun handle(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceShellCaller()

        val safeExtras = extras ?: Bundle.EMPTY
        val log = StringBuilder()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return failure("Headless wireless adb commands require Android 11+", log)
        }

        return try {
            when (method) {
                METHOD_ADB_PAIR -> pair(arg, safeExtras, log)
                METHOD_ADB_START -> start(safeExtras, log)
                METHOD_ADB_PAIR_AND_START -> pairAndStart(arg, safeExtras, log)
                METHOD_ADB_PAIRING_NOTIFY_START -> startPairingNotification(log)
                METHOD_ADB_PAIRING_NOTIFY_STOP -> stopPairingNotification(log)
                else -> failure("Unsupported method: $method", log)
            }
        } catch (tr: Throwable) {
            LOGGER.e(tr, "headless adb method=$method")
            failure(tr.message ?: tr.javaClass.name, log, tr)
        }
    }

    private fun pair(pairingCode: String?, extras: Bundle, log: StringBuilder): Bundle {
        val code = pairingCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras.getString(EXTRA_PAIRING_CODE)?.trim().orEmpty()
        require(code.isNotEmpty()) { "pairingCode is required" }

        val host = readString(extras, EXTRA_HOST, DEFAULT_HOST)
        val timeout = readLong(extras, EXTRA_DISCOVERY_TIMEOUT, DEFAULT_DISCOVERY_TIMEOUT)
        val key = createKey()
        val pairingPort = readInt(extras, EXTRA_PAIRING_PORT) ?: discoverPort(AdbMdns.TLS_PAIRING, timeout, log)
            ?: error("Pairing port not found")

        log.appendLine("Pairing with wireless debugging at $host:$pairingPort ...")
        AdbPairingClient(host, pairingPort, code, key).use {
            check(it.start()) { "Pairing failed" }
        }
        log.appendLine("Pairing completed.")

        return success(
            log,
            Bundle().apply {
                putString(EXTRA_HOST, host)
                putInt(EXTRA_PAIRING_PORT, pairingPort)
            }
        )
    }

    private fun start(extras: Bundle, log: StringBuilder): Bundle {
        val host = readString(extras, EXTRA_HOST, DEFAULT_HOST)
        val discoveryTimeout = readLong(extras, EXTRA_DISCOVERY_TIMEOUT, DEFAULT_DISCOVERY_TIMEOUT)
        val startTimeout = readLong(extras, EXTRA_START_TIMEOUT, DEFAULT_START_TIMEOUT)
        val waitForServer = readBoolean(extras, EXTRA_WAIT_FOR_SERVER, true)
        val enableTcpip5555 = readBoolean(extras, EXTRA_ENABLE_TCPIP_5555, DEFAULT_ENABLE_TCPIP_5555)
        val key = createKey()
        val connectPort = readInt(extras, EXTRA_CONNECT_PORT) ?: discoverPort(AdbMdns.TLS_CONNECT, discoveryTimeout, log)
            ?: error("Wireless adb connect port not found")

        val startServerResult = startServer(host, connectPort, key, enableTcpip5555, discoveryTimeout, log)
        val finalConnectPort = startServerResult.connectPort

        val serverReady = if (waitForServer) waitForServer(startTimeout, log) else Shizuku.pingBinder()
        if (waitForServer && !serverReady) {
            return failure("Shizuku binder was not received after startup", log)
        }
        if (serverReady) {
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            log.appendLine("Saved launch mode as ADB for boot auto-start.")
        }

        return success(
            log,
            Bundle().apply {
                putString(EXTRA_HOST, host)
                putInt(EXTRA_CONNECT_PORT, finalConnectPort)
                putBoolean("server_ready", serverReady)
                startServerResult.connectPortBeforeRestart?.let {
                    putInt("connect_port_before_restart", it)
                }
                if (enableTcpip5555) {
                    putInt("tcpip_port", 5555)
                }
            }
        )
    }

    private fun pairAndStart(pairingCode: String?, extras: Bundle, log: StringBuilder): Bundle {
        val pairResult = pair(pairingCode, extras, log)
        if (!pairResult.getBoolean(EXTRA_OK)) {
            return pairResult
        }

        val connectResult = start(extras, log)
        if (pairResult.containsKey(EXTRA_PAIRING_PORT)) {
            connectResult.putInt(EXTRA_PAIRING_PORT, pairResult.getInt(EXTRA_PAIRING_PORT))
        }
        return connectResult
    }

    private fun startPairingNotification(log: StringBuilder): Bundle {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(AdbPairingService.startIntent(appContext))
        } else {
            appContext.startService(AdbPairingService.startIntent(appContext))
        }
        log.appendLine("Started pairing notification service.")
        return success(log)
    }

    private fun stopPairingNotification(log: StringBuilder): Bundle {
        appContext.stopService(AdbPairingService.stopIntent(appContext))
        log.appendLine("Stopped pairing notification service.")
        return success(log)
    }

    private fun createKey(): AdbKey {
        return AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
    }

    private data class StartServerResult(
        val connectPort: Int,
        val connectPortBeforeRestart: Int?
    )

    private fun startServer(
        host: String,
        connectPort: Int,
        key: AdbKey,
        enableTcpip5555: Boolean,
        discoveryTimeout: Long,
        log: StringBuilder
    ): StartServerResult {
        val output = StringBuilder()
        log.appendLine("Connecting to wireless adb at $host:$connectPort ...")
        var finalConnectPort = connectPort
        var connectPortBeforeRestart: Int? = null

        AdbClient(host, connectPort, key).use { client ->
            client.connect()
            log.appendLine("Connected to wireless adb.")
            client.shellCommand(starterCommand()) {
                val text = String(it)
                output.append(text)
                log.append(text)
                if (!text.endsWith('\n')) {
                    log.appendLine()
                }
            }
            if (enableTcpip5555) {
                enableClassicTcpipAdb(client) { line ->
                    log.appendLine(line)
                }
                connectPortBeforeRestart = connectPort
                val refreshedConnectPort = rediscoverConnectPortAfterAdbdRestart(discoveryTimeout, log)
                if (refreshedConnectPort != null) {
                    finalConnectPort = refreshedConnectPort
                    if (refreshedConnectPort != connectPort) {
                        log.appendLine("Wireless adb connect port changed after adbd restart: $connectPort -> $refreshedConnectPort")
                    } else {
                        connectPortBeforeRestart = null
                        log.appendLine("Wireless adb connect port remains $connectPort after adbd restart.")
                    }
                } else {
                    log.appendLine("Could not rediscover connect port after adbd restart. Keep using previous port $connectPort.")
                }
            }
        }

        if (output.isEmpty()) {
            log.appendLine("Starter command returned no output.")
        }

        return StartServerResult(
            connectPort = finalConnectPort,
            connectPortBeforeRestart = connectPortBeforeRestart
        )
    }

    private fun rediscoverConnectPortAfterAdbdRestart(timeoutMillis: Long, log: StringBuilder): Int? {
        val settleMillis = minOf(1_500L, timeoutMillis.coerceAtLeast(0L))
        if (settleMillis > 0) {
            try {
                Thread.sleep(settleMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val rediscoveryTimeout = maxOf(2_000L, timeoutMillis - settleMillis)
        log.appendLine("Re-discovering wireless adb connect port after adbd restart ...")
        return discoverPort(AdbMdns.TLS_CONNECT, rediscoveryTimeout, log)
    }

    private fun starterCommand(): String {
        val starterFile = File(appContext.applicationInfo.nativeLibraryDir, "libshizuku.so")
        return "${starterFile.absolutePath} --apk=${appContext.applicationInfo.sourceDir}"
    }

    private fun discoverPort(serviceType: String, timeoutMillis: Long, log: StringBuilder): Int? {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(appContext, serviceType, Observer {
            if (it in 1..65535 && port.compareAndSet(-1, it)) {
                latch.countDown()
            }
        })

        log.appendLine("Discovering ${serviceType.removePrefix("_").removeSuffix("._tcp")} port ...")
        mdns.start()

        return try {
            if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                port.get().also {
                    log.appendLine("Discovered port $it for $serviceType.")
                }
            } else {
                log.appendLine("No port discovered for $serviceType within ${timeoutMillis}ms.")
                null
            }
        } finally {
            mdns.stop()
        }
    }

    private fun waitForServer(timeoutMillis: Long, log: StringBuilder): Boolean {
        if (Shizuku.pingBinder()) {
            log.appendLine("Shizuku binder is already available.")
            return true
        }

        val latch = CountDownLatch(1)
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                latch.countDown()
            }
        }

        Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)
        return try {
            if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                log.appendLine("Shizuku binder received.")
                true
            } else {
                log.appendLine("Timed out waiting for Shizuku binder after ${timeoutMillis}ms.")
                false
            }
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    private fun enforceShellCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != 0) {
            throw SecurityException("Only shell/root may call this API (uid=$uid)")
        }
    }

    private fun success(log: StringBuilder, extras: Bundle = Bundle()): Bundle {
        extras.putBoolean(EXTRA_OK, true)
        extras.putString(EXTRA_LOG, log.toString().trim())
        return extras
    }

    private fun failure(message: String, log: StringBuilder, throwable: Throwable? = null): Bundle {
        return Bundle().apply {
            putBoolean(EXTRA_OK, false)
            putString(EXTRA_MESSAGE, message)
            putString(EXTRA_ERROR, throwable?.javaClass?.name)
            putString(EXTRA_LOG, log.toString().trim())
        }
    }

    private fun readString(extras: Bundle, key: String, defaultValue: String): String {
        return extras.getString(key)?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    private fun readInt(extras: Bundle, key: String): Int? {
        if (!extras.containsKey(key)) return null

        val value = extras.get(key)
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun readLong(extras: Bundle, key: String, defaultValue: Long): Long {
        if (!extras.containsKey(key)) return defaultValue

        val value = extras.get(key)
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun readBoolean(extras: Bundle, key: String, defaultValue: Boolean): Boolean {
        if (!extras.containsKey(key)) return defaultValue

        val value = extras.get(key)
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", true) || value == "1"
            is Int -> value != 0
            else -> defaultValue
        }
    }
}
