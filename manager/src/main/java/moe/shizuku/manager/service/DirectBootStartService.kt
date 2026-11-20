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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.LockedBootReceiver.Companion.TAG
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
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
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            log("STAGE2: adb_wifi_enabled=1, starting mDNS discovery (20s timeout)")
            discoverPort()
        }
        log("Discovered/selected port=$port")

        log("STAGE3: AdbStarter.startAdb on port $port")
        AdbStarter.startAdb(applicationContext, port) { line -> log("adb> ${line.trimEnd()}") }
        log("STAGE4: waiting for binder (up to 60s)")
        Starter.waitForBinder { line -> log("binder> ${line.trimEnd()}") }
    }

    private suspend fun discoverPort(): Int = withTimeout(20_000) {
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
