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

        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_DISCOVERY_TIMEOUT = 15_000L
        private const val DEFAULT_START_TIMEOUT = 10_000L
    }

    private val appContext = context.applicationContext

    init {
        ShizukuSettings.initialize(appContext)
    }

    fun canHandle(method: String): Boolean {
        return method == METHOD_ADB_PAIR
            || method == METHOD_ADB_START
            || method == METHOD_ADB_PAIR_AND_START
    }

    fun handle(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceShellCaller()

        val safeExtras = extras ?: Bundle.EMPTY
        val log = StringBuilder()

        return try {
            when (method) {
                METHOD_ADB_PAIR -> pair(arg, safeExtras, log)
                METHOD_ADB_START -> start(safeExtras, log)
                METHOD_ADB_PAIR_AND_START -> pairAndStart(arg, safeExtras, log)
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
        val key = createKey()
        val connectPort = readInt(extras, EXTRA_CONNECT_PORT) ?: discoverPort(AdbMdns.TLS_CONNECT, discoveryTimeout, log)
            ?: error("Wireless adb connect port not found")

        startServer(host, connectPort, key, log)

        val serverReady = if (waitForServer) waitForServer(startTimeout, log) else Shizuku.pingBinder()
        if (waitForServer && !serverReady) {
            return failure("Shizuku binder was not received after startup", log)
        }

        return success(
            log,
            Bundle().apply {
                putString(EXTRA_HOST, host)
                putInt(EXTRA_CONNECT_PORT, connectPort)
                putBoolean("server_ready", serverReady)
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

    private fun createKey(): AdbKey {
        return AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
    }

    private fun startServer(host: String, connectPort: Int, key: AdbKey, log: StringBuilder) {
        val output = StringBuilder()
        log.appendLine("Connecting to wireless adb at $host:$connectPort ...")

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
        }

        if (output.isEmpty()) {
            log.appendLine("Starter command returned no output.")
        }
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
