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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.LockedBootReceiver.Companion.TAG
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct-boot-aware foreground service that reproduces AdbStartWorker's logic WITHOUT
 * WorkManager — because WorkManager/JobScheduler do not dispatch before first unlock.
 *
 * Experiment harness. Every stage is logged BOTH to logcat (tag "ShizukuDirectBoot")
 * and, more importantly, appended to a file in DEVICE-ENCRYPTED storage:
 *
 *     <device-protected files>/directboot_log.txt
 *
 * That file survives reboots, first-unlock, and the USB adb-authorization resets that
 * drop a live logcat session. Pull it AFTER unlocking with:
 *
 *     adb shell run-as moe.shizuku.privileged.api cat /data/user_de/0/moe.shizuku.privileged.api/files/directboot_log.txt
 *
 *   (If run-as is blocked on your build, use:)
 *     adb pull /data/user_de/0/moe.shizuku.privileged.api/files/directboot_log.txt
 *   (needs a shell that can read app DE storage; run-as is the reliable one for a
 *    debuggable build. The path's "0" is the user id — adjust if not user 0. The
 *    applicationId is moe.shizuku.privileged.api; change if your fork renames it.)
 *
 * Stages logged:
 *   1. write adb_enabled / adb_wifi_enabled   (needs WRITE_SECURE_SETTINGS)
 *   2. AdbMdns discovers the TLS port          (phone discovering its own advert; needs network)
 *   3. AdbClient.connect() + key auth           (the keystore-while-locked question)
 *   4. server binder comes up
 *
 * PATCH NOTE (Wi-Fi timing):
 *   AdbDebuggingManager evaluates "adb_wifi_enabled" ONCE, at the moment the setting changes.
 *   If the device is not yet connected to a (trusted) Wi-Fi network at that instant, it logs
 *   "Not connected to any wireless network. Not enabling adbwifi." and never retries.
 *   Early in a locked boot Wi-Fi is usually not up yet, so we now (a) wait for Wi-Fi first,
 *   and (b) toggle adb_wifi_enabled 0 -> 1 on every attempt so the framework re-evaluates.
 *
 * PATCH NOTE (no double start):
 *   After unlock the BOOT_COMPLETED receiver starts this service again. If the locked-boot run
 *   already brought the server up, the second run used to kill and restart it. Now it checks
 *   Shizuku.pingBinder() first and skips.
 */
class DirectBootStartService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val channelId = "DirectBootStart"
    private val notifId = 1449

    // Device-encrypted-storage log file. Resolved lazily so it uses the DE context.
    private val logFile: File by lazy {
        val deContext =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext()
            else this
        File(deContext.filesDir, "directboot_log.txt")
    }

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Append one line to both logcat and the DE-storage file. */
    private fun log(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.i(TAG, msg)
        try {
            val line = buildString {
                append(tsFormat.format(Date()))
                append("  ")
                append(msg)
                if (tr != null) {
                    append("  | ")
                    append(tr.toString())
                }
                append('\n')
            }
            logFile.appendText(line)
        } catch (e: Exception) {
            // Never let logging crash the experiment.
            Log.e(TAG, "failed to write log file: $e")
        }
    }

    companion object {
        // How long to wait for Wi-Fi to come up before the first attempt (locked boot can be slow).
        private const val WIFI_WAIT_MS = 90_000L

        // Extra settle time after Wi-Fi reports available, so the framework's own
        // WifiManager connection info (BSSID) is populated when it checks.
        private const val WIFI_SETTLE_MS = 1_500L

        // Per-attempt mDNS discovery window, and how many toggle+discover attempts we make.
        private const val DISCOVER_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 4

        // After unlock, how long to wait for the binder of an already-running server to arrive
        // before concluding that no server is running. (The binder is delivered via the
        // ShizukuProvider, which only works after unlock; in measurements it arrived <1s
        // after unlock and this service starts ~4s after unlock.)
        private const val ALREADY_RUNNING_WAIT_MS = 6_000L

        fun start(context: Context) {
            val i = Intent(context, DirectBootStartService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Start a fresh run marker. We append (not truncate) so multiple boots accumulate,
        // which is useful for comparing locked vs. unlocked runs.
        log("========================================")
        log("DirectBootStartService.onCreate (new run)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand")
        startForeground(notifId, buildNotification())

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        log("keyguardLocked=${km.isKeyguardLocked} userUnlocked=${isUserUnlockedCompat()} sdk=${Build.VERSION.SDK_INT}")

        scope.launch {
            try {
                runStart()
                log("runStart() COMPLETED OK — server should be up")
            } catch (e: Exception) {
                log("runStart() FAILED", e)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runStart() {
        val cr: ContentResolver = contentResolver

        // PATCH: if the locked-boot run already started the server, the BOOT_COMPLETED run after
        // unlock must not kill and restart it (that caused a ~3s outage a few seconds after
        // unlock). Only possible to check once the user is unlocked (binder delivery needs it).
        if (isUserUnlockedCompat()) {
            val alreadyRunning = withTimeoutOrNull(ALREADY_RUNNING_WAIT_MS) {
                while (!Shizuku.pingBinder()) delay(250)
                true
            } ?: false
            if (alreadyRunning) {
                log("Server already running (binder alive) - skipping start")
                return
            }
            log("No running server detected after unlock - starting normally")
        }

        // STAGE 1: enable adb + wifi debugging via WRITE_SECURE_SETTINGS.
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) {
            log("STAGE1 FAIL: WRITE_SECURE_SETTINGS not granted")
            return
        }
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        log("STAGE1 OK: adb_enabled=1")

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        log("getAdbTcpPort=$tcpPort tcpMode=${ShizukuSettings.getTcpMode()} wifiRequired=${EnvironmentUtils.isWifiRequired()}")

        val port: Int = if (!EnvironmentUtils.isWifiRequired()) {
            log("Using fixed TCP port $tcpPort, skipping mDNS")
            tcpPort
        } else {
            enableWifiDebuggingAndDiscoverPort(cr)
        }
        log("Discovered/selected port=$port")

        log("STAGE3: AdbStarter.startAdb on port $port")
        AdbStarter.startAdb(applicationContext, port) { line -> log("adb> ${line.trimEnd()}") }
        log("STAGE4: waiting for binder (up to 60s)")
        Starter.waitForBinder { line -> log("binder> ${line.trimEnd()}") }
    }

    /**
     * STAGE 2 (patched): wait for Wi-Fi, then toggle adb_wifi_enabled 0 -> 1 and look for the
     * TLS port via mDNS. Retries a few times, because the framework only evaluates the setting
     * at the moment it changes.
     */
    private suspend fun enableWifiDebuggingAndDiscoverPort(cr: ContentResolver): Int {
        val wifiUp = awaitWifi(WIFI_WAIT_MS)
        log("STAGE2: Wi-Fi available=$wifiUp (waited up to ${WIFI_WAIT_MS / 1000}s)")
        if (wifiUp) delay(WIFI_SETTLE_MS)

        for (attempt in 1..MAX_ATTEMPTS) {
            // Toggle so the framework sees a real change and re-evaluates the Wi-Fi state.
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(500)
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            log("STAGE2: adb_wifi_enabled 0->1 (attempt $attempt/$MAX_ATTEMPTS), mDNS discovery (${DISCOVER_TIMEOUT_MS / 1000}s)")
            try {
                return discoverPort(DISCOVER_TIMEOUT_MS)
            } catch (e: TimeoutCancellationException) {
                log("STAGE2: mDNS discovery timed out on attempt $attempt")
                if (!wifiUp) {
                    // Wi-Fi may have come up while we were waiting; give it another chance.
                    if (awaitWifi(5_000)) delay(WIFI_SETTLE_MS)
                }
            }
        }
        throw IllegalStateException("adb wireless port not discovered after $MAX_ATTEMPTS attempts")
    }

    /**
     * Suspends until a Wi-Fi network is available, or [timeoutMs] elapses.
     * Returns true if Wi-Fi is up. Never throws: on any problem it logs and returns false.
     */
    private suspend fun awaitWifi(timeoutMs: Long): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Already on Wi-Fi?
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                log("awaitWifi: already on Wi-Fi")
                return true
            }

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
            // Most likely ACCESS_NETWORK_STATE is missing from the manifest.
            log("awaitWifi: SecurityException (add ACCESS_NETWORK_STATE?) — falling back to a fixed wait", e)
            delay(timeoutMs.coerceAtMost(30_000L))
            false
        } catch (e: Exception) {
            log("awaitWifi: unexpected error", e)
            false
        }
    }

    private suspend fun discoverPort(timeoutMs: Long): Int = withTimeout(timeoutMs) {
        callbackFlow {
            val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                if (p.second > 0) {
                    log("mDNS found host=${p.first} port=${p.second}")
                    trySend(p.second)
                }
            }
            adbMdns.start()
            awaitClose { adbMdns.stop() }
        }.first()
    }

    private fun isUserUnlockedCompat(): Boolean {
        return try {
            val um = getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.isUserUnlocked
        } catch (e: Exception) { false }
    }

    override fun onDestroy() {
        log("onDestroy")
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            channelId,
            "Direct Boot start (debug)",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Shizuku: trying pre-unlock start")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
