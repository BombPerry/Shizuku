package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.service.DirectBootStartService
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * Boot entry point for the pre-unlock experiment.
 *
 * Listens for BOTH:
 *   - ACTION_LOCKED_BOOT_COMPLETED : fires BEFORE first unlock (Direct Boot). Needs
 *     android:directBootAware="true". Only device-encrypted storage is available.
 *   - ACTION_BOOT_COMPLETED        : fires AFTER first unlock. Fallback, so we can tell
 *     "receiver never fires" apart from "only the locked broadcast is withheld".
 *
 * It writes a line to the device-encrypted log file for EVERY delivery, BEFORE the
 * launch-mode check, so the log shows the receiver firing even if setup didn't persist
 * (getLastLaunchMode() == UNKNOWN). That distinguishes three failure modes:
 *   (a) no log line at all        -> broadcast not delivered (stopped state / device policy)
 *   (b) line with action=...BOOT_COMPLETED only, never LOCKED_... -> locked broadcast withheld
 *   (c) line present but "mode != ADB" -> receiver fine, setup/prefs didn't persist
 *
 * Pull the log after unlock:
 *   adb shell run-as moe.shizuku.privileged.api cat files/directboot_log.txt
 */
class LockedBootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ShizukuDirectBoot"
        private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /** Append to the SAME device-encrypted log file the service uses. */
        private fun logToFile(context: Context, msg: String) {
            try {
                val deContext =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext()
                    else context
                val f = File(deContext.filesDir, "directboot_log.txt")
                f.appendText("${tsFormat.format(Date())}  [receiver] $msg\n")
            } catch (e: Exception) {
                Log.e(TAG, "receiver failed to write log file: $e")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "LockedBootReceiver.onReceive action=$action user=${UserHandleCompat.myUserId()}")
        // Log EVERY delivery before any gating, so a fired-but-skipped receiver is visible.
        logToFile(context, "onReceive action=$action user=${UserHandleCompat.myUserId()}")

        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (UserHandleCompat.myUserId() > 0) {
            logToFile(context, "skip: secondary user")
            return
        }

        val mode = ShizukuSettings.getLastLaunchMode()
        if (mode != LaunchMethod.ADB) {
            logToFile(context, "skip: launch mode != ADB (=$mode) — setup may not have persisted")
            return
        }

        logToFile(context, "starting DirectBootStartService (triggered by $action)")
        Log.i(TAG, "Starting DirectBootStartService")
        DirectBootStartService.start(context)
    }
}
