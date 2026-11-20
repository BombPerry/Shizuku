package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.PairingAutopilotService
import android.util.Log

/**
 * Entry point for the experimental one-tap pairing autopilot (phone/tablet only —
 * this fork's existing [moe.shizuku.manager.home.showAccessibilityDialog] /
 * `AdbPairingAccessibilityService` already covers TV via a passive reader).
 *
 * Mirrors the consent flow in AccessibilityDialogHelper.kt (same
 * `WRITE_SECURE_SETTINGS` self-enable path) but targets [PairingAutopilotService] and,
 * once the service is live, actually starts the run instead of just describing where
 * to navigate.
 */
fun Context.startPairingAutopilot() {
    if (PairingAutopilotService.isRunning) {
        Toast.makeText(this, R.string.autopilot_already_running, Toast.LENGTH_SHORT).show()
        return
    }

    if (isAutopilotEnabled()) {
        confirmAndRun()
        return
    }

    val hasWriteSecureSettings =
        checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    val installer = packageManager.getInstallerPackageName(packageName)
    val isInstalledByPlayOrAdb = (installer == "com.android.vending") || (installer == null)
    // On Android 14+, sideloaded installs have accessibility toggles gated behind
    // "restricted settings" until the user allows them from App info. Play/ADB installs and
    // older versions aren't gated, so they can skip straight to the enable dialog.
    val restrictedSettingsGate =
        !isInstalledByPlayOrAdb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    if (hasWriteSecureSettings) {
        if (enableAutopilotService()) {
            confirmAndRun()
            return
        }
    }

    if (restrictedSettingsGate) {
        showRestrictedSettingsDialog()
    } else {
        showEnableAutopilotDialog()
    }
}

private fun Context.confirmAndRun() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.autopilot_title)
        .setMessage(R.string.autopilot_confirm_message)
        .setPositiveButton(R.string.autopilot_run) { _, _ ->
            Log.i("PairAutopilot", "User confirmed autopilot run")
            PairingAutopilotService.requestRun(this)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.showEnableAutopilotDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.autopilot_title)
        .setMessage(R.string.autopilot_enable_message)
        .setPositiveButton(R.string.enable) { _, _ ->
            // Arm first: the run begins the moment the service connects, so the user
            // does not have to come back to the app after flipping the toggle.
            PairingAutopilotService.requestRun(this)
            moe.shizuku.manager.utils.SettingsPage.Accessibility.launch(this)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.showRestrictedSettingsDialog() {
    // The "Allow restricted settings" item only appears in App info AFTER the user has
    // visited the accessibility screen and been blocked there. So the flow is: open the
    // accessibility screen (Enable) → get blocked → Open App info to lift the restriction →
    // return to the accessibility screen and enable the service. Both actions are offered.
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.autopilot_title)
        .setMessage(R.string.autopilot_restricted_settings_app_info)
        .setPositiveButton(R.string.enable) { _, _ ->
            PairingAutopilotService.requestRun(this)
            moe.shizuku.manager.utils.SettingsPage.Accessibility.launch(this)
        }
        .setNeutralButton(R.string.autopilot_open_app_info) { _, _ ->
            openAppInfo()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.openAppInfo() {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }.onFailure {
        Log.w("PairAutopilot", "Could not open App info page", it)
        Toast.makeText(this, R.string.autopilot_open_app_info_failed, Toast.LENGTH_LONG).show()
    }
}

private fun Context.autopilotComponentName(): String =
    "$packageName/${PairingAutopilotService::class.java.canonicalName}"

private fun Context.enabledAccessibilityServices(): List<String> =
    Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(":")
        ?: emptyList()

private fun Context.isAutopilotEnabled(): Boolean =
    enabledAccessibilityServices().any { it.equals(autopilotComponentName(), ignoreCase = true) }

private fun Context.enableAutopilotService(): Boolean {
    if (isAutopilotEnabled()) return true

    val existing = enabledAccessibilityServices().filter { it.isNotBlank() }
    val updated = (existing + autopilotComponentName()).joinToString(":")

    return runCatching {
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated
        )
        isAutopilotEnabled()
    }.getOrElse {
        Log.w("PairAutopilot", "Could not self-enable accessibility service", it)
        false
    }
}
