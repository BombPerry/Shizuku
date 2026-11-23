package moe.shizuku.manager.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPortHistory
import moe.shizuku.manager.adb.AdbPortRange
import moe.shizuku.manager.adb.AdbPortScanner
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.BootLog
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/**
 * Direct-boot-aware foreground service that starts the Shizuku server at boot, in both phases:
 * before first unlock (LOCKED_BOOT_COMPLETED) and after it (BOOT_COMPLETED).
 *
 * It does not use WorkManager, because WorkManager/JobScheduler do not dispatch before first
 * unlock. If an attempt fails after unlock, it hands over to the normal AdbStartWorker path
 * (ShizukuReceiverStarter), which has the retry logic and the "Attempt now" notification.
 *
 * Flow:
 *   0. After unlock: is the server already running? How long that check waits depends on
 *      whether a start command was already sent earlier in this boot (per-boot marker).
 *   1. ROOT launch mode -> start via su. Works in either phase where root is available.
 *   2. ADB launch mode:
 *        a. fixed TCP port (TCP mode)   -> loopback connect, no Wi-Fi / mDNS needed.
 *        b. wireless debugging (TLS)    -> wait for Wi-Fi, switch adb_wifi_enabled on and watch
 *                                          it: if Android switches it straight back off, the
 *                                          attempt was refused and is retried shortly; if it stays
 *                                          on, the port is taken from mDNS or from the
 *                                          service.adb.tls.port property, whichever comes first.
 *   3. Before unlock: once the start command is sent, wait briefly for the binder and log whether
 *      it arrived. After unlock: wait for the binder.
 *
 * Every step is logged via [BootLog] to a device-encrypted file, see BootLog for how to read it.
 *
 * NOTE (Wi-Fi timing, from Dimlos):
 *   AdbDebuggingManager evaluates "adb_wifi_enabled" ONCE, at the moment the setting changes.
 *   If the device is not connected to a (trusted) Wi-Fi network at that instant, it logs
 *   "Not connected to any wireless network. Not enabling adbwifi.", writes adb_wifi_enabled
 *   back to 0 and never retries by itself. Early in a locked boot Wi-Fi is usually not up yet,
 *   so we wait for Wi-Fi and re-trigger the setting until it is accepted.
 *
 * NOTE (no double start):
 *   - After unlock, a run first checks Shizuku.pingBinder() and skips the start if the
 *     locked-boot run already brought the server up (from Dimlos).
 *   - If a run is still in progress when the next boot broadcast arrives (e.g. still waiting
 *     for Wi-Fi when the user unlocks), the new request is ignored and the running attempt
 *     simply continues.
 */
class DirectBootStartService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var runJob: Job? = null

    /** Outcome of one attempt to switch on wireless debugging and find its port. */
    private sealed class WirelessAttempt {
        data class Found(val port: Int, val source: String) : WirelessAttempt()
        object Refused : WirelessAttempt()
        object NoPort : WirelessAttempt()
    }

    companion object {
        private const val CHANNEL_ID = "BootStart"
        private const val NOTIFICATION_ID = 1449

        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        // How long to wait for Wi-Fi to come up before the first attempt (locked boot can be slow).
        private const val WIFI_WAIT_MS = 90_000L

        // Total time we keep re-triggering wireless debugging after Wi-Fi is up.
        private const val WIRELESS_BUDGET_MS = 120_000L

        // Pause before retrying after Android refused to enable wireless debugging. Short at
        // first (Wi-Fi info usually becomes available within a second or two), longer later.
        private const val REFUSED_RETRY_SHORT_MS = 1_000L
        private const val REFUSED_RETRY_LONG_MS = 3_000L
        private const val REFUSED_SHORT_RETRIES = 5

        // How long to look for the port while the switch stays on. Accepted attempts find the
        // port within ~1-2s, so the first window is short; if nothing answers (very early in
        // boot the framework or mDNS may not be ready yet) each further window is 2s longer,
        // up to the old 15s.
        private const val DISCOVER_TIMEOUT_FIRST_MS = 4_000L
        private const val DISCOVER_TIMEOUT_STEP_MS = 2_000L
        private const val DISCOVER_TIMEOUT_MAX_MS = 15_000L

        // After unlock, how long to wait for the binder of an already-running server to arrive
        // before concluding that no server is running. (The binder is delivered via the
        // ShizukuProvider; in Dimlos's measurements it arrived <1s after unlock and this service
        // starts ~4s after unlock.) Only used if a start was sent earlier in this boot.
        private const val ALREADY_RUNNING_WAIT_MS = 6_000L

        // Quick check when no start was sent earlier in this boot (e.g. started manually).
        private const val QUICK_RUNNING_CHECK_MS = 500L

        // Before unlock, how long to wait for the binder after sending the start command.
        private const val LOCKED_CONFIRM_MS = 5_000L

        // Per-boot marker in device-encrypted storage.
        private const val MARKER_PREFS = "boot_start"
        private const val MARKER_KEY = "start_sent_boot_count"

        fun start(context: Context) {
            val i = Intent(context, DirectBootStartService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private fun log(msg: String, tr: Throwable? = null) = BootLog.log(this, "[service] $msg", tr)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called for every startForegroundService(), even ignored ones.
        startForeground(NOTIFICATION_ID, buildNotification())

        if (runJob?.isActive == true) {
            log("start requested while a run is still in progress - letting that run continue")
            return START_NOT_STICKY
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        log("==== new run: keyguardLocked=${km.isKeyguardLocked} userUnlocked=${isUserUnlocked()} sdk=${Build.VERSION.SDK_INT} bootCount=${currentBootCount()}")

        runJob = scope.launch {
            try {
                runStart()
            } catch (e: Exception) {
                log("run FAILED", e)
                handOffAfterFailure()
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runStart() {
        // STEP 0: if a previous run already started the server, don't kill and restart it
        // (that caused a ~3s outage a few seconds after unlock). Only checkable after unlock.
        if (isUserUnlocked()) {
            val sentThisBoot = startSentThisBoot()
            val wait = if (sentThisBoot == false) QUICK_RUNNING_CHECK_MS else ALREADY_RUNNING_WAIT_MS
            log("checking for a running server (start sent earlier this boot: ${sentThisBoot ?: "unknown"}, waiting up to ${wait}ms)")
            if (isAlreadyRunning(wait)) {
                log("server already running (binder alive) - nothing to do")
                return
            }
        }

        val sent = when (val mode = ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> startWithRoot()
            LaunchMethod.ADB -> startWithAdb()
            else -> {
                log("no launch mode saved (=$mode) - nothing to do")
                false
            }
        }
        if (!sent) return
        markStartSentThisBoot()

        // STEP 3
        if (!isUserUnlocked()) {
            if (isAlreadyRunning(LOCKED_CONFIRM_MS)) {
                log("binder received while locked - server confirmed before unlock")
            } else {
                log("start command sent while locked, no binder within ${LOCKED_CONFIRM_MS / 1000}s - the BOOT_COMPLETED run will confirm")
            }
            return
        }
        log("waiting for binder (up to 60s)")
        Starter.waitForBinder { line -> log("binder> ${line.trimEnd()}") }
        log("server is running")
    }

    /** STEP 1: root. Same command as ShizukuReceiverStarter.rootStart. Returns true once sent. */
    private fun startWithRoot(): Boolean {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            throw IllegalStateException("launch mode is ROOT but no root shell is available")
        }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        log("ROOT: running starter")
        val result = Shell.cmd(Starter.internalCommand).exec()
        result.out.forEach { log("root> $it") }
        result.err.forEach { log("root! $it") }
        log("ROOT: starter exit code ${result.code}")
        return true
    }

    /**
     * STEP 2: adb, either via a fixed TCP port or via wireless debugging.
     * Returns true once the start command was sent, false if adb start isn't possible here.
     */
    private suspend fun startWithAdb(): Boolean {
        val cr: ContentResolver = contentResolver

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() || tcpPort > 0
        if (!supported) {
            log("ADB: background start not supported on this device (needs Android 11+ or a TCP port)")
            return false
        }

        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) {
            log("ADB: WRITE_SECURE_SETTINGS not granted")
            ShizukuReceiverStarter.showPermissionErrorNotification(applicationContext)
            return false
        }
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        log("ADB: adb_enabled=1, tcpPort=$tcpPort tcpMode=${ShizukuSettings.getTcpMode()} wifiRequired=${EnvironmentUtils.isWifiRequired()}")

        val port: Int = if (!EnvironmentUtils.isWifiRequired()) {
            log("ADB: using fixed TCP port $tcpPort, skipping Wi-Fi and mDNS")
            tcpPort
        } else {
            enableWifiDebuggingAndFindPort(cr)
        }

        log("ADB: connecting on port $port")
        AdbStarter.startAdb(applicationContext, port) { line -> log("adb> ${line.trimEnd()}") }
        return true
    }

    /**
     * Waits for Wi-Fi, then keeps switching wireless debugging on until Android accepts it,
     * and returns its port.
     *
     * Android answers a refused attempt (not connected to Wi-Fi yet, or untrusted network) by
     * writing adb_wifi_enabled back to 0 right away, so a refusal is noticed within milliseconds
     * instead of after a discovery timeout.
     */
    private suspend fun enableWifiDebuggingAndFindPort(cr: ContentResolver): Int {
        val wifiUp = awaitWifi(WIFI_WAIT_MS)
        log("ADB: Wi-Fi available=$wifiUp (waited up to ${WIFI_WAIT_MS / 1000}s)")

        // Scan order: measured range (Settings), then ports found at earlier boots, then the rest.
        val history = AdbPortHistory.load(this)
        val calibration = AdbPortRange.load(this)
        val oddOnly = AdbPortRange.isOddOnlyScan(this)
        val scanOrder = AdbPortScanner.buildOrder(history, calibration, oddOnly)
        log("ADB: scan order: ${AdbPortScanner.describe(scanOrder, history, calibration, oddOnly)}")

        val deadline = SystemClock.elapsedRealtime() + WIRELESS_BUDGET_MS
        var attempt = 0
        var refusals = 0
        var unanswered = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++

            // The framework only reacts to a change, so make sure the setting is 0 first.
            if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1) {
                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0)
                delay(500)
            }

            val window = (DISCOVER_TIMEOUT_FIRST_MS + unanswered * DISCOVER_TIMEOUT_STEP_MS)
                .coerceAtMost(DISCOVER_TIMEOUT_MAX_MS)
            log("ADB: attempt $attempt - switching wireless debugging on (waiting up to ${window}ms for a port)")
            when (val result = tryEnableWirelessDebugging(cr, window, scanOrder)) {
                is WirelessAttempt.Found -> {
                    log("ADB: port ${result.port} found via ${result.source} on attempt $attempt")
                    AdbPortHistory.record(this, result.port)
                    return result.port
                }
                WirelessAttempt.Refused -> {
                    refusals++
                    val pause = if (refusals <= REFUSED_SHORT_RETRIES) REFUSED_RETRY_SHORT_MS else REFUSED_RETRY_LONG_MS
                    log("ADB: refused by Android (not connected to a trusted Wi-Fi yet?), retrying in ${pause}ms")
                    if (refusals == REFUSED_SHORT_RETRIES + 1) {
                        log("ADB: still refused - if this keeps happening, wireless debugging may not be 'always allowed' on this network")
                    }
                    delay(pause)
                    if (!isOnWifi()) awaitWifi(REFUSED_RETRY_LONG_MS)
                }
                WirelessAttempt.NoPort -> {
                    unanswered++
                    log("ADB: switch stayed on, but no port within ${window}ms - retrying")
                }
            }
        }
        throw IllegalStateException("wireless debugging port not found within ${WIRELESS_BUDGET_MS / 1000}s ($attempt attempts, $refusals refused)")
    }

    /**
     * Switches adb_wifi_enabled to 1 and waits for the first of:
     *   - Android writing it back to 0           -> Refused
     *   - the loopback port scan finding adbd    -> Found("scan")
     *   - mDNS advertising the TLS connect port  -> Found("mDNS")
     *   - [timeoutMs] passing                     -> NoPort
     * The observer is registered before the write, so a fast refusal can't be missed.
     * (service.adb.tls.port would be quicker still, but apps can't read it: SELinux, adbd_prop.)
     */
    private suspend fun tryEnableWirelessDebugging(
        cr: ContentResolver,
        timeoutMs: Long,
        scanOrder: IntArray,
    ): WirelessAttempt =
        withTimeoutOrNull(timeoutMs) {
            callbackFlow<WirelessAttempt> {
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 0) {
                            trySend(WirelessAttempt.Refused)
                        }
                    }
                }
                cr.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, observer)

                val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                    if (p.second > 0) trySend(WirelessAttempt.Found(p.second, "mDNS"))
                }
                adbMdns.start()

                val scanner = AdbPortScanner({ log("ADB: $it") }, scanOrder) { p ->
                    trySend(WirelessAttempt.Found(p, "scan"))
                }

                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
                scanner.start()

                awaitClose {
                    scanner.stop()
                    adbMdns.stop()
                    cr.unregisterContentObserver(observer)
                }
            }.first()
        } ?: WirelessAttempt.NoPort

    private fun isOnWifi(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Suspends until a Wi-Fi network is available, or [timeoutMs] elapses.
     * Returns true if Wi-Fi is up. Never throws: on any problem it logs and returns false.
     */
    private suspend fun awaitWifi(timeoutMs: Long): Boolean {
        return try {
            if (isOnWifi()) {
                log("awaitWifi: already on Wi-Fi")
                return true
            }
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val result = withTimeoutOrNull(timeoutMs) {
                callbackFlow {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(true)
                        }
                    }
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    cm.registerNetworkCallback(request, callback)
                    awaitClose { cm.unregisterNetworkCallback(callback) }
                }.first()
            }
            if (result == true) log("awaitWifi: Wi-Fi became available") else log("awaitWifi: timed out")
            result == true
        } catch (e: SecurityException) {
            log("awaitWifi: SecurityException (ACCESS_NETWORK_STATE missing?) - falling back to a fixed wait", e)
            delay(timeoutMs.coerceAtMost(30_000L))
            false
        } catch (e: Exception) {
            log("awaitWifi: unexpected error", e)
            false
        }
    }

    /** Waits up to [timeoutMs] for the binder of a running server. */
    private suspend fun isAlreadyRunning(timeoutMs: Long = ALREADY_RUNNING_WAIT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!Shizuku.pingBinder()) delay(250)
            true
        } ?: false

    // ---- per-boot marker (device-encrypted, so it works before unlock) ----

    private fun currentBootCount(): Int =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun markerPrefs() =
        createDeviceProtectedStorageContext().getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)

    private fun markStartSentThisBoot() {
        val bootCount = currentBootCount()
        if (bootCount >= 0) markerPrefs().edit().putInt(MARKER_KEY, bootCount).commit()
    }

    /** true/false if known, null if the boot count isn't available on this device. */
    private fun startSentThisBoot(): Boolean? {
        val bootCount = currentBootCount()
        if (bootCount < 0) return null
        return markerPrefs().getInt(MARKER_KEY, -1) == bootCount
    }

    /**
     * Before unlock: do nothing, the BOOT_COMPLETED run after unlock tries again.
     * After unlock: if the server still isn't up, hand over to the normal AdbStartWorker path,
     * which retries with backoff and shows the "Attempt now" notification.
     */
    private suspend fun handOffAfterFailure() {
        if (!isUserUnlocked()) {
            log("failure while locked - the BOOT_COMPLETED run will retry after unlock")
            return
        }
        if (isAlreadyRunning()) {
            log("server is running despite the error - nothing to hand off")
            ShizukuStateMachine.update()
            return
        }
        log("handing over to ShizukuReceiverStarter (AdbStartWorker retries)")
        ShizukuStateMachine.update()
        ShizukuReceiverStarter.start(applicationContext)
    }

    private fun isUserUnlocked(): Boolean = try {
        (getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Start on boot",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Starting Shizuku…")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
