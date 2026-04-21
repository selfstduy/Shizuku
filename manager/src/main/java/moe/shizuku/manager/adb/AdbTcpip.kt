package moe.shizuku.manager.adb

private const val TCPIP_PORT = 5555

private const val PREPARE_TCPIP_COMMAND = "" +
    "setprop service.adb.tcp.port $TCPIP_PORT; " +
    "setprop persist.adb.tcp.port $TCPIP_PORT; " +
    "settings put global adb_enabled 1; " +
    "settings put global adb_wifi_enabled 1; " +
    "settings put global adb_allowed_connection_time 0"

private const val RESTART_ADBD_COMMAND = "setprop ctl.restart adbd"

fun enableClassicTcpipAdb(client: AdbClient, logLine: (String) -> Unit) {
    logLine("Enabling classic ADB over TCP on port $TCPIP_PORT...")

    val output = StringBuilder()
    try {
        client.shellCommand(PREPARE_TCPIP_COMMAND) {
            output.append(String(it))
        }
    } catch (tr: Throwable) {
        logLine("Failed to configure TCP/IP 5555: ${tr.message ?: tr.javaClass.name}")
        return
    }

    if (output.isNotBlank()) {
        logLine(output.toString().trim())
    }

    // Restart adbd to apply tcpip port changes.
    // Disconnect during restart is expected and not treated as failure.
    try {
        client.shellCommand(RESTART_ADBD_COMMAND) {
            output.append(String(it))
        }
    } catch (_: Throwable) {
    }

    logLine("TCP/IP 5555 configured. You can run: adb connect <device-ip>:5555")
}

