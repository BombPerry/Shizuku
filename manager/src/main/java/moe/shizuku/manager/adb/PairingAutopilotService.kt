package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.SettingsPage
import android.util.Log
import java.net.ConnectException

/**
 * Experimental one-tap pairing autopilot, for phones (this fork's existing
 * [AdbPairingAccessibilityService] already covers TV via `com.android.tv.settings`; this
 * targets `com.android.settings` instead, so the two never compete for the same screen).
 *
 * Drives the system Settings UI to complete the whole wireless-debugging pairing dance
 * that the user would otherwise do by hand:
 *
 *   1. Enable Developer options (tap Build number until unlocked), if not already on
 *   2. Open Developer options / the Wireless debugging entry
 *   3. Flip the Wireless debugging toggle on (accepting the per-network prompt)
 *   4. Open the Wireless debugging detail screen
 *   5. Tap "Pair device with pairing code"
 *   6. Read the pairing code + port off the dialog and pair via [AdbPairingClient]
 *
 * Every step is separated by [STEP_DELAY_MS] and announced via Log + a Toast, so the
 * flow can be watched and traced on-device while it is still experimental.
 *
 * Debug with:  `adb logcat -s PairAutopilot`
 */
class PairingAutopilotService : AccessibilityService() {

    companion object {
        private const val TAG = "PairAutopilot"

        /**
         * Pause between every UI action.
         *
         * Deliberately slow (3s) so each step can be observed while debugging. Drop this
         * to ~600-800ms once the flow is proven; the waits exist to let Settings finish
         * its screen transitions, not because the work needs the time.
         */
        // Fixed pause between autopilot steps, purely to let Settings' screen transitions
        // begin before the next step reads the UI. 3s was very conservative; 1.2s still
        // comfortably covers a screen transition while trimming ~12s off a 7-step run.
        // This is NOT a correctness wait — the load-bearing settling is done by
        // waitUntilStable() and the targeted delay()s inside each step, which are left as-is.
        const val STEP_DELAY_MS = 1_200L

        /** How long to keep looking for the pairing dialog before giving up. */
        private const val PAIRING_CODE_TIMEOUT_MS = 60_000L

        /** Max taps on Build number. AOSP needs 7; a few spare covers OEM counters. */
        private const val MAX_BUILD_NUMBER_TAPS = 12

        /** Max scroll attempts when hunting for an off-screen row. */
        private const val MAX_SCROLLS = 10

        /** How long to wait for live search results to populate before giving up. */
        private const val SEARCH_RESULT_TIMEOUT_MS = 8_000L

        /** How long to watch for a post-toggle confirmation dialog (Allow/OK) to appear. */
        private const val DIALOG_WAIT_MS = 4_000L


        /** How long to keep looking for the search bar after the Settings page launches. */
        private const val SEARCH_BAR_FIND_TIMEOUT_MS = 5_000L

        /**
         * View-id suffixes for the Settings search entry point, matched with endsWith so
         * the package prefix (com.android.settings: / com.samsung...: etc.) doesn't matter.
         * The user confirmed `com.android.settings:id/search_action_bar` on their device;
         * the others cover stock and common skins.
         */
        private val SEARCH_VIEW_IDS = listOf(
            "id/search_action_bar",
            "id/search_action_bar_title",
            "id/search_bar",
            "id/search",
            "id/action_bar_search"
        )

        /** contentDescription fallbacks when no known search id is present. */
        private val SEARCH_HINTS = listOf("search settings", "search")

        private const val PREF_PENDING = "pairing_autopilot_pending"

        @Volatile
        private var instance: PairingAutopilotService? = null

        /** True while a run is in flight, so the button can't kick off two at once. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * Ask the autopilot to run. If the service is already connected it starts
         * immediately; otherwise a flag is left behind and the run begins as soon as the
         * user enables the service in Accessibility settings.
         */
        fun requestRun(context: Context) {
            val live = instance
            if (live != null) {
                live.startRun()
            } else {
                Log.i(TAG, "Service not connected yet, arming pending flag")
                ShizukuSettings.getPreferences()?.edit()?.putBoolean(PREF_PENDING, true)?.apply()
            }
        }

        private fun consumePending(): Boolean {
            val prefs = ShizukuSettings.getPreferences() ?: return false
            val pending = prefs.getBoolean(PREF_PENDING, false)
            if (pending) prefs.edit().putBoolean(PREF_PENDING, false).apply()
            return pending
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null

    private val strings by lazy { SettingsUiStrings.get(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Autopilot service connected")

        if (consumePending()) {
            Log.i(TAG, "Pending run flag found, starting autopilot")
            startRun()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service is driven by an imperative coroutine
        // (see runAutopilot) rather than reacting to individual events. Events would
        // arrive faster than the deliberate step pacing and make the flow hard to follow.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Autopilot service unbound")
        runJob?.cancel()
        scope.cancel()
        instance = null
        isRunning = false
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- the flow

    fun startRun() {
        if (isRunning) {
            Log.w(TAG, "Run already in progress, ignoring")
            return
        }
        isRunning = true
        runJob = scope.launch {
            try {
                runAutopilot()
            } catch (t: Throwable) {
                Log.e(TAG, "Autopilot aborted", t)
                announce(getString(R.string.autopilot_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun runAutopilot() {
        announce(getString(R.string.autopilot_step_start))
        pause()

        // ---- Step 1: developer options -------------------------------------
        if (developerOptionsEnabled()) {
            Log.i(TAG, "Developer options already enabled, skipping unlock")
        } else {
            announce(getString(R.string.autopilot_step_dev_options))
            unlockDeveloperOptions()
            if (!developerOptionsEnabled()) {
                fail(getString(R.string.autopilot_failed_dev_options))
                return
            }
        }
        pause()

        // ---- Step 2: open the wireless debugging entry ----------------------
        announce(getString(R.string.autopilot_step_open_devsettings))
        // Reuses the app's existing, ROM-aware deep link (handles the MIUI/HyperOS
        // fallback path) rather than rolling a second intent chain here.
        withContext(Dispatchers.Main) {
            SettingsPage.Developer.HighlightWirelessDebugging.launch(this@PairingAutopilotService)
        }
        pause()

        // ---- Step 3: toggle wireless debugging on ---------------------------
        // USB debugging is intentionally NOT toggled here. Shizuku requires USB debugging,
        // but it's enabled later via the adb shell that starts the server (see
        // AdbStarter.startAdb), where the command runs as the shell UID and inherently holds
        // WRITE_SECURE_SETTINGS. Driving that row through the Settings UI was unreliable:
        // on some ROMs the enabled USB-debugging preference isn't exposed as a findable
        // accessibility node, so the scroll-hunt could never locate it. Enabling it over the
        // shell sidesteps the UI entirely and satisfies Shizuku's precondition at start time.
        announce(getString(R.string.autopilot_step_toggle))
        if (!enableWirelessDebugging()) {
            fail(getString(R.string.autopilot_failed_toggle))
            return
        }
        pause()

        // ---- Step 4: enter the wireless debugging screen --------------------
        announce(getString(R.string.autopilot_step_open_detail))
        if (!openWirelessDebuggingDetail()) {
            fail(getString(R.string.autopilot_failed_detail))
            return
        }
        pause()

        // ---- Step 5: tap "Pair device with pairing code" --------------------
        announce(getString(R.string.autopilot_step_pair_entry))
        if (!tapPairWithCode()) {
            fail(getString(R.string.autopilot_failed_pair_entry))
            return
        }
        pause()

        // ---- Step 6: scrape the dialog and pair -----------------------------
        announce(getString(R.string.autopilot_step_reading_code))
        val creds = awaitPairingCredentials()
        if (creds == null) {
            fail(getString(R.string.autopilot_failed_no_code))
            return
        }
        Log.i(TAG, "Found pairing port=%d".format(creds.port))

        announce(getString(R.string.autopilot_step_pairing))
        pair(creds)
    }

    // ------------------------------------------------------- step 1: dev opts

    private fun developerOptionsEnabled(): Boolean {
        return Settings.Global.getInt(
            contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }

    /**
     * Opens Settings, reaches Build number, and taps it until Developer options unlock.
     *
     * Reaching Build number is the fragile part: on many OEM skins (Samsung especially)
     * it sits below the fold, or one level deeper under "Software information". Scrolling
     * a Settings list via accessibility is unreliable across skins, so we try the Settings
     * search bar FIRST (tap it, type the localized "Build number" label, wait for the live
     * results to populate, tap the match) and only fall back to scroll-hunting if search
     * is unavailable or yields nothing.
     *
     * Note: if the device has a screen lock, Android throws up a credential prompt part
     * way through that no accessibility service can answer. We detect the stall and hand
     * back to the user rather than tapping into the void.
     */
    private suspend fun unlockDeveloperOptions() {
        // Open the TOP-LEVEL Settings page, not About phone: the search bar lives on the
        // main page, and search is our preferred way to reach Build number. If search
        // fails we navigate into About phone ourselves in the fallback path below.
        withContext(Dispatchers.Main) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            }.onFailure { Log.e(TAG, "Could not open Settings", it) }
        }
        pause()

        // --- Preferred path: jump straight to Build number via the Settings search bar.
        // Skips all OEM scroll/hierarchy differences (About phone -> Software information
        // -> Build number on Samsung, vs directly on About phone for stock).
        var reachedBuildNumber = navigateViaSearch(strings.buildNumber)

        // --- Fallback path: open About phone and scroll-hunt, including the Samsung
        // sub-page hop into Software information (which itself needs scrolling to reach).
        if (!reachedBuildNumber) {
            Log.i(TAG, "Search navigation failed, falling back to About phone + scroll")
            withContext(Dispatchers.Main) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                }.onFailure { Log.e(TAG, "Could not open About phone", it) }
            }
            pause()

            var buildNode = findNodeScrolling(strings.buildNumber)
            if (buildNode == null) {
                // Samsung and others: Build number is under Software information, which is
                // itself below the fold — findNodeScrolling handles the scroll to reach it.
                Log.i(TAG, "Build number not directly visible, trying Software information")
                val softwareNode = findNodeScrolling(strings.softwareInfo)
                if (softwareNode != null) {
                    clickNodeOrAncestor(softwareNode)
                    pause()
                    buildNode = findNodeScrolling(strings.buildNumber)
                }
            }
            reachedBuildNumber = buildNode != null
        }

        if (!reachedBuildNumber) {
            Log.w(TAG, "Could not reach Build number by search or scroll")
        }

        var taps = 0
        while (taps < MAX_BUILD_NUMBER_TAPS && !developerOptionsEnabled()) {
            val node = findNodeScrolling(strings.buildNumber)
            if (node == null) {
                Log.w(TAG, "Build number row not found (tap %d)".format(taps + 1))
                break
            }
            val clicked = clickNodeOrAncestor(node)
            taps++
            Log.d(TAG, "Build number tap %d (dispatched=%b)".format(taps, clicked))

            // The unlock counter needs taps in quick succession; the full step delay
            // would let it reset. Keep these tight but still visible.
            delay(400)

            if (credentialPromptVisible()) {
                announce(getString(R.string.autopilot_needs_screen_lock))
                // Wait for the user to authenticate before continuing to tap.
                var waited = 0L
                while (credentialPromptVisible() && waited < 30_000L) {
                    delay(1_000)
                    waited += 1_000
                }
            }
        }
        Log.i(TAG, "Build number tapped %d times, devOptions=%b".format(taps, developerOptionsEnabled()))
    }

    /**
     * Uses the system Settings search bar to jump to a destination row, bypassing scroll
     * and OEM sub-page nesting.
     *
     * Flow: find and click the search entry point -> locate the resulting edit field ->
     * set its text to the target label -> wait for the live result list to populate (the
     * user reports results are live but can lag) -> click the first result that matches.
     *
     * Returns true only if a matching result was found AND tapped. Any failure (no search
     * bar on this ROM, no edit field, no results within the timeout) returns false so the
     * caller can fall back to scrolling.
     */
    private suspend fun navigateViaSearch(needles: List<String>): Boolean {
        // 1. Find the search entry point, retrying across several frames. On a cold
        //    Settings launch the search bar may not be laid out on the first frame, and
        //    rootInActiveWindow can briefly still point at the previous screen. A single
        //    attempt here was causing a false "not found" and an unwanted jump to the
        //    About-phone fallback, so we poll.
        var searchEntry: AccessibilityNodeInfo? = null
        var findWaited = 0L
        while (findWaited < SEARCH_BAR_FIND_TIMEOUT_MS) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString()?.contains("settings") == true) {
                searchEntry =
                    // Preferred: a node whose visible TEXT or description is "search" (or
                    // "search settings"). This is what MacroDroid surfaces, and it doesn't
                    // depend on the node being marked clickable — clickRobust handles that.
                    collectNodes(root).firstOrNull { node ->
                        SettingsUiStrings.matches(node.text, SEARCH_HINTS) ||
                            SettingsUiStrings.matches(node.contentDescription, SEARCH_HINTS)
                    }
                    // Next: the known search-bar view ids (search_action_bar etc.).
                    ?: findByViewId(root, SEARCH_VIEW_IDS)
            }
            if (searchEntry != null) break
            delay(500)
            findWaited += 500
        }

        if (searchEntry == null) {
            Log.i(TAG, "No search bar found after %dms".format(SEARCH_BAR_FIND_TIMEOUT_MS))
            return false
        }
        Log.i(TAG, "Search entry found (text='%s' desc='%s'), opening".format(
            searchEntry.text ?: "", searchEntry.contentDescription ?: ""
        ))

        // The Settings search bar is frequently a NON-clickable container whose real
        // click target is a descendant, or one that only responds to a touch gesture.
        // clickNodeOrAncestor only walks upward, so it silently no-ops here — this was the
        // cause of "search bar found but nothing happens". Use the robust opener instead.
        val opened = clickRobust(searchEntry)
        Log.d(TAG, "Search bar open attempt (ok=%b)".format(opened))
        delay(1_200) // let the search activity/screen open

        // 2. Locate the editable field. It may be the entry we tapped, or a new EditText
        //    on the search screen that just opened. Retry across a couple of frames.
        var editField: AccessibilityNodeInfo? = null
        var editWaited = 0L
        while (editWaited < 3_000L) {
            editField = findEditText(rootInActiveWindow)
            if (editField != null) break
            delay(500)
            editWaited += 500
        }
        if (editField == null) {
            Log.w(TAG, "Search opened but no edit field found")
            return false
        }

        // 3. Type a TRUNCATED query into the search field.
        //    We drop the last character of the target label ("build number" -> "build
        //    numbe"). The search FIELD then contains "build numbe", which does NOT match
        //    the needle "build number" — but the RESULT ROW still shows the full "build
        //    number" and does match. This removes the field-echo/result ambiguity: the
        //    only node matching the full needle is the real result, so we can safely take
        //    the first match.
        val fullQuery = needles.firstOrNull() ?: return false
        val typedQuery = if (fullQuery.length > 1) fullQuery.dropLast(1) else fullQuery
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                typedQuery
            )
        }
        val typed = editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Typed '%s' into search (ok=%b)".format(typedQuery, typed))
        if (!typed) return false

        // Press enter/search, for devices that DON'T live-filter as you type. Harmless on
        // devices that do (the query is already applied). ACTION_IME_ENTER lives on the
        // nested AccessibilityAction class (not as a bare int on AccessibilityNodeInfo) and
        // is API 30+, so reference it by its full path and guard the call. minSdk here is
        // 24; older devices are overwhelmingly live-search anyway, so skipping is fine.
        delay(500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val entered = editField.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            Log.d(TAG, "Pressed IME enter (ok=%b)".format(entered))
        }

        // 4. Wait for results, then tap the "Build number" RESULT ROW. The field holds the
        //    truncated "build numbe"; the real result shows the full "build number".
        //    IMPORTANT: match against the FULL primary label only (not the abbreviated
        //    variants like "build no"), because the truncated echo "build numbe" still
        //    *contains* "build no" and would otherwise match the search field itself.
        val exactLabel = needles.firstOrNull() ?: return false
        var waited = 0L
        while (waited < SEARCH_RESULT_TIMEOUT_MS) {
            delay(700)
            waited += 700

            val current = rootInActiveWindow ?: continue
            val result = collectNodes(current).firstOrNull { node ->
                node.className?.toString()?.contains("EditText") != true &&
                    (nodeTextContains(node.text, exactLabel) ||
                        nodeTextContains(node.contentDescription, exactLabel))
            }

            if (result != null) {
                Log.i(TAG, "Search result found after %dms, tapping".format(waited))
                val ok = clickRobust(result)
                delay(1_500)
                return ok
            }
        }
        Log.w(TAG, "No matching search result within %dms".format(SEARCH_RESULT_TIMEOUT_MS))
        return false
    }

    /** Case-insensitive contains check against a single exact label, punctuation-flattened. */
    private fun nodeTextContains(candidate: CharSequence?, label: String): Boolean {
        val text = SettingsUiStrings.normalize(candidate)
        if (text.isEmpty()) return false
        return text.contains(SettingsUiStrings.normalize(label))
    }

    /** Finds the first node whose view-id resource name ends with any of [idSuffixes]. */
    private fun findByViewId(root: AccessibilityNodeInfo?, idSuffixes: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        return collectNodes(root).firstOrNull { node ->
            val id = node.viewIdResourceName ?: return@firstOrNull false
            idSuffixes.any { id.endsWith(it) }
        }
    }

    /** Finds the first editable text field in the tree. */
    private fun findEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        return collectNodes(root).firstOrNull { node ->
            node.isEditable || node.className?.toString()?.contains("EditText") == true
        }
    }

    private fun credentialPromptVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        return pkg.contains("settings") &&
            collectNodes(root).any { node ->
                node.className?.toString()?.contains("EditText") == true && node.isPassword
            }
    }

    // ------------------------------------------------- step 3/4: the toggle

    /**
     * Scrolls Developer options to the Wireless debugging row and turns its switch on.
     *
     * The switch sits to the RIGHT of the "Wireless debugging" label, as a sibling within
     * the same list row — not inside the label's own parent chain. So rather than walking
     * up from the text (which can miss a sibling switch), we find the row container that
     * holds the label and search the whole subtree for the checkable node. If the row has
     * no switch in-tree (some skins put it in a separate node), we fall back to the
     * checkable node whose vertical position best lines up with the label.
     *
     * Returns true if the switch ended up on (or already was).
     */
    private suspend fun enableWirelessDebugging(): Boolean =
        enableToggle(strings.wirelessDebugging, "wireless debugging")

    /**
     * Generic "find a labelled row in Developer options and make sure its switch is on".
     * Used for both Wireless debugging and USB debugging, which are identical row+switch
     * shapes. Returns true if the switch ended up on (or already was).
     */
    private suspend fun enableToggle(needles: List<String>, label: String): Boolean {
        repeat(3) { attempt ->
            val row = findNodeScrolling(needles)
            if (row == null) {
                Log.w(TAG, "%s row not found (attempt %d)".format(label, attempt + 1))
                // Fallback: some skins (seen on Samsung One UI) don't expose the Wireless
                // debugging row's own title node to accessibility, so it can never be found
                // by text even when visible. But the rows ABOVE it (Revoke USB debugging
                // authorisations) and BELOW it (Disable adb authorisation timeout) usually
                // ARE exposed. If we can find both anchors adjacent in the expected order,
                // the missing row sits in the gap between them — tap that gap. Only attempted
                // for the wireless-debugging toggle, and heavily guarded (see below) so it
                // can't blindly tap the wrong row.
                if (needles === strings.wirelessDebugging && tapBetweenAnchors()) {
                    Log.i(TAG, "%s: tapped gap between anchor rows (fallback)".format(label))
                    delay(1_500)
                    confirmDialogIfPresent()
                    waitUntilStable()
                    // We can't verify via the row's own switch (it's not exposed), so treat a
                    // successful gap tap as success and let the next step (opening the detail
                    // screen / pairing) confirm whether wireless debugging actually turned on.
                    return true
                }
                delay(1_000)
                return@repeat
            }

            // Re-acquire the row AFTER the list has settled, and refresh it before reading
            // bounds. findNodeScrolling can return a node found mid-fling; the trace showed
            // its bounds still shifting afterwards, which is what made the switch-line band
            // point at the neighbouring (Revoke) row. Re-finding on the settled screen and
            // refreshing gives coordinates that match what's actually on screen now.
            waitUntilStable()
            val settledRow = (findNode(needles) ?: row).also { it.refresh() }
            val labelRect = android.graphics.Rect().also { settledRow.getBoundsInScreen(it) }

            // Find the switch on this row PURELY to read its on/off state and confirm this is
            // a toggle row. We do NOT tap it directly — on this ROM the switch widget often
            // doesn't expose isCheckable/ACTION_CLICK, so tapping it no-ops (that's why
            // nothing toggled). Instead we tap the whole ROW, which Settings implements as a
            // single togglable preference: tapping anywhere on it flips the switch.
            val switchNode = findToggleForRow(settledRow, labelRect)

            if (switchNode != null && switchNode.isChecked) {
                Log.i(TAG, "%s already on".format(label))
                return true
            }
            if (switchNode == null) {
                Log.w(TAG, "%s: no switch located on row; will tap row anyway".format(label))
            }

            // The clickable target is the row preference container, not the label text and
            // not the switch. Climb from the label to the nearest clickable ancestor (the
            // list item), then tap THAT. Falls back to a gesture tap on the row's centre-left
            // (over the text, safely away from the switch) if no clickable ancestor exists.
            waitUntilStable()
            val ok = clickToggleRow(settledRow, labelRect, switchNode)
            Log.d(TAG, "%s row tap (ok=%b)".format(label, ok))
            delay(1_500)
            confirmDialogIfPresent()

            // Verify it actually flipped; if not, let the loop retry. Re-find and refresh
            // the row so the switch is read from the current screen, not the pre-tap bounds.
            waitUntilStable()
            val afterRow = findNode(needles)?.also { it.refresh() }
            val afterRect = afterRow?.let { android.graphics.Rect().also { r -> it.getBoundsInScreen(r) } }
            val after = if (afterRow != null && afterRect != null) findToggleForRow(afterRow, afterRect) else null
            if (after == null || after.isChecked) {
                return true
            }
            Log.w(TAG, "%s still off after tap, retrying".format(label))
            delay(500)
        }
        return false
    }

    /**
     * Fallback for skins that don't expose the Wireless debugging row's title to
     * accessibility. Locates the Revoke row (anchor ABOVE) and the "Disable adb
     * authorisation timeout" row (anchor BELOW) and taps the vertical gap between them,
     * where Wireless debugging must sit per Developer options' fixed row order.
     *
     * Heavily guarded so it never taps a wrong row:
     *  - both anchors must be present and on screen,
     *  - the timeout anchor must be BELOW the revoke anchor (correct order),
     *  - the gap between them must be big enough to contain a row but not so big that
     *    multiple rows could fit (which would mean the layout isn't what we assume).
     * If any guard fails we do nothing and return false, leaving the caller to retry or
     * fail normally rather than tap blindly.
     *
     * @return true only if a gap tap was actually dispatched.
     */
    private suspend fun tapBetweenAnchors(): Boolean {
        val above = findNode(strings.revokeAdbKeys)
        val below = findNode(strings.adbTimeout)
        if (above == null || below == null) {
            Log.w(TAG, "gap fallback: anchors not both visible (above=%b below=%b)"
                .format(above != null, below != null))
            return false
        }

        val aRect = android.graphics.Rect().also { above.refresh(); above.getBoundsInScreen(it) }
        val bRect = android.graphics.Rect().also { below.refresh(); below.getBoundsInScreen(it) }

        // The timeout row must be below the revoke row.
        if (bRect.top <= aRect.bottom) {
            Log.w(TAG, "gap fallback: anchors not in expected order (revoke.bottom=%d timeout.top=%d)"
                .format(aRect.bottom, bRect.top))
            return false
        }

        val gap = bRect.top - aRect.bottom
        // Expected gap ≈ one row. Use the anchors' own heights as the scale: a single row is
        // roughly as tall as an anchor row. Accept between ~0.5 and ~2.5 anchor-heights so a
        // normal one-row gap passes but a multi-row gap (wrong assumption) is rejected.
        val rowH = maxOf(aRect.height(), bRect.height(), 1)
        if (gap < rowH / 2 || gap > rowH * 5 / 2) {
            Log.w(TAG, "gap fallback: gap %d px not ~one row (rowH=%d); refusing to tap".format(gap, rowH))
            return false
        }

        val tapX = (aRect.left + aRect.right) / 2f
        val tapY = (aRect.bottom + bRect.top) / 2f
        Log.d(TAG, "gap fallback: tapping midpoint x=%.0f y=%.0f (gap=%d rowH=%d)".format(tapX, tapY, gap, rowH))
        return gestureTap(tapX, tapY)
    }

    /**
     * Taps a toggle control. Two row shapes exist in Developer options, and they need
     * different targets (this is the difference visible between Wireless debugging and USB
     * debugging in the layout inspector):
     *
     *  - NAVIGATE+TOGGLE rows (e.g. Wireless debugging): the switch is a SEPARATE node with
     *    its own bounds, divided from the label. Tapping the label navigates into a detail
     *    screen; only tapping the SWITCH toggles. So when we have a distinct switch node
     *    whose bounds sit clearly to the right of the label, we tap the switch's centre.
     *
     *  - TOGGLE-ONLY rows (e.g. USB debugging): the whole row is one preference; tapping
     *    anywhere flips it. Here we tap the row's clickable ancestor.
     *
     * Order: try ACTION_CLICK on the switch node → gesture-tap the switch's centre →
     * ACTION_CLICK on the row ancestor → gesture-tap the label. Each step is guarded so a
     * no-op falls through to the next.
     */
    private suspend fun clickToggleRow(
        label: AccessibilityNodeInfo,
        labelRect: android.graphics.Rect,
        switchNode: AccessibilityNodeInfo?
    ): Boolean {
        // If there's a distinct switch node sitting to the right of the label, prefer it:
        // this is the only target that toggles a navigate+toggle row.
        if (switchNode != null) {
            switchNode.refresh()
            val sr = android.graphics.Rect().also { switchNode.getBoundsInScreen(it) }
            val labelRight = labelRect.right
            val switchIsToRight = sr.left >= labelRight - (labelRect.width() * 0.2f)

            // Try a node click on the switch first (works if it exposes ACTION_CLICK).
            if (switchNode.isClickable && switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "clickToggleRow: clicked switch node")
                return true
            }
            // Otherwise gesture-tap the switch's centre, if its bounds look valid and right.
            if (sr.width() > 0 && sr.height() > 0 && switchIsToRight) {
                Log.d(TAG, "clickToggleRow: gesture-tapping switch at (%d,%d)".format(sr.centerX(), sr.centerY()))
                return gestureTap(sr.centerX().toFloat(), sr.centerY().toFloat())
            }
        }

        // Toggle-only row (or no usable switch node): tap the row preference container.
        var node: AccessibilityNodeInfo? = label
        var hops = 0
        while (node != null && hops < 8) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "clickToggleRow: clicked row ancestor")
                return true
            }
            node = node.parent
            hops++
        }

        // Last resort: gesture-tap over the label text (left third of the row).
        label.refresh()
        val r = android.graphics.Rect().also { label.getBoundsInScreen(it) }
        if (r.width() > 0 && r.height() > 0) {
            val x = (r.left + r.width() * 0.25f)
            val y = r.centerY().toFloat()
            Log.d(TAG, "clickToggleRow: gesture-tapping row label at (%.0f,%.0f)".format(x, y))
            return gestureTap(x, y)
        }
        return false
    }

    /**
     * Blocks until the visible screen text stops changing between samples, i.e. all scroll
     * momentum has died and the layout is static. Prevents acting on stale coordinates.
     */
    private suspend fun waitUntilStable(
        maxWaitMs: Long = 3_000L,
        sampleMs: Long = 300L,
        requiredStableSamples: Int = 2
    ) {
        var previous = screenTextSignature()
        var stableCount = 0
        var waited = 0L
        while (waited < maxWaitMs) {
            delay(sampleMs)
            waited += sampleMs
            val current = screenTextSignature()
            if (current == previous && current.isNotEmpty()) {
                stableCount++
                if (stableCount >= requiredStableSamples) {
                    Log.d(TAG, "Screen stable after %dms".format(waited))
                    return
                }
            } else {
                stableCount = 0
            }
            previous = current
        }
        Log.d(TAG, "Screen still moving after %dms, proceeding anyway".format(maxWaitMs))
    }

    /**
     * Finds the switch/checkable node belonging to a given label row.
     *
     * Strategy 1: the nearest ancestor that contains BOTH the label and a checkable node
     * (the actual list-row container), then the checkable node within it — this is the
     * switch to the right of the text.
     *
     * Strategy 2: among all checkable nodes on screen, the one whose vertical centre is
     * closest to the label's, i.e. the one on the same visual line.
     */
    private fun findToggleForRow(
        label: AccessibilityNodeInfo,
        labelRect: android.graphics.Rect
    ): AccessibilityNodeInfo? {
        // Strategy 1 (STRUCTURAL — scroll-proof): climb from the label to the nearest
        // ancestor whose subtree also contains a switch, and return that switch.
        //
        // This deliberately uses TREE CONTAINMENT, not screen coordinates. Matching a switch
        // by "is it on the label's Y line?" is unreliable: a RecyclerView keeps scrolling for
        // a beat after the row is found, so coordinate bounds read a moment later can point at
        // the neighbouring row and tap the wrong one. Within one preference row container
        // there is exactly one switch, so once we find the container that holds THIS label,
        // its switch is unambiguously this row's — no coordinates, no mid-scroll misattribution.
        //
        // The climb starts at the label's parent and is bounded. The first ancestor whose
        // subtree contains a switch is the row container (in the AOSP tree, the row's
        // clickable LinearLayout holding the title RelativeLayout and the widget_frame →
        // switchWidget as siblings). We stop at the FIRST single-switch container, so we never
        // climb up into the shared RecyclerView and grab a different row's switch.
        var container: AccessibilityNodeInfo? = label.parent
        var hops = 0
        while (container != null && hops < 6) {
            val subtree = collectNodes(container)
            // Guard against climbing into a multi-row container: only accept this container
            // if it holds exactly one switch. A container with several switches is a shared
            // ancestor (e.g. the RecyclerView), not a single row.
            val switches = subtree.filter { it.isCheckableSwitch() }
            if (switches.size == 1) {
                Log.d(TAG, "findToggleForRow: matched switch in row container (strategy 1, hops=%d)"
                    .format(hops))
                return switches.first()
            }
            if (switches.size > 1) {
                // We've climbed past the row into a shared container; stop climbing and let
                // the coordinate fallback disambiguate rather than returning a random switch.
                break
            }
            container = container.parent
            hops++
        }

        // Strategy 2 (COORDINATE FALLBACK): reached if no single-switch ancestor container
        // was found. Here we must use geometry, but we RE-READ the label's bounds now rather
        // than trusting the possibly-stale labelRect, and require a switch whose centre
        // overlaps the freshly-read label line. If nothing overlaps we return null rather
        // than guess.
        label.refresh()
        val freshRect = android.graphics.Rect().also { label.getBoundsInScreen(it) }
        val tolerance = freshRect.height().coerceAtLeast(16)
        val bandTop = freshRect.top - tolerance
        val bandBottom = freshRect.bottom + tolerance
        val labelCenterY = freshRect.centerY()

        val root = rootInActiveWindow ?: return null
        val onLine = collectNodes(root).filter { node ->
            if (!node.isCheckableSwitch()) return@filter false
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            r.centerY() in bandTop..bandBottom
        }
        if (onLine.isEmpty()) {
            Log.w(TAG, "findToggleForRow: no switch in row container and none overlaps label line (band %d..%d)"
                .format(bandTop, bandBottom))
            return null
        }
        val best = onLine.minByOrNull { node ->
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            kotlin.math.abs(r.centerY() - labelCenterY)
        }
        Log.d(TAG, "findToggleForRow: matched switch by fresh-coordinate overlap (strategy 2)")
        return best
    }

    /** True if the node is a switch/checkbox/toggle we can flip. */
    private fun AccessibilityNodeInfo.isCheckableSwitch(): Boolean {
        if (isCheckable) return true
        val cls = className?.toString().orEmpty()
        if (cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton") ||
            cls.contains("Compat") // SwitchCompat / SwitchMaterial etc.
        ) return true
        // Some OEM widgets don't set isCheckable or a standard class name but do carry a
        // resource id that names the switch. Match that as a last signal.
        val id = viewIdResourceName?.lowercase().orEmpty()
        return id.contains("switch") || id.contains("toggle") || id.endsWith(":id/checkbox")
    }

    /** Accepts the per-network "Allow wireless debugging?" dialog when it appears. */
    /**
     * Watches briefly for a confirmation dialog and taps its positive button.
     *
     * Covers both toggle dialogs seen on this ROM — "Allow USB debugging?" (OK / Cancel)
     * and "Allow wireless debugging on this network?" (Allow / Cancel) — plus other OEMs'
     * wording, by matching the button TEXT against allow/ok/yes rather than a fixed label.
     *
     * Three things the old one-shot check got wrong, fixed here:
     *  - The dialog takes a moment to appear, so we POLL for a short window instead of
     *    checking once and returning.
     *  - The positive button's clickable node may be a container, not the text node, so we
     *    match on text but tap via clickRobust (which walks to a clickable ancestor / taps
     *    by coordinates).
     *  - We only accept a button whose text is one of the positive words AND avoid the
     *    negative one ("cancel"), so we never dismiss by pressing Cancel.
     */
    private suspend fun confirmDialogIfPresent() {
        var waited = 0L
        while (waited < DIALOG_WAIT_MS) {
            val root = rootInActiveWindow
            if (root != null) {
                val nodes = collectNodes(root)

                // Match ONLY a button, not arbitrary dialog text. The dialog TITLE ("Allow
                // wireless debugging on this network?") starts with "allow", so matching by
                // startsWith or contains would grab the title. Require:
                //   - the text EQUALS a positive word (allow / ok / yes), not just starts
                //     with it — a title is a whole sentence, a button is one word; and
                //   - the node looks like a button (Button class, or clickable, or short).
                fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
                    val cls = node.className?.toString().orEmpty()
                    if (cls.contains("Button")) return true
                    if (node.isClickable) return true
                    // A short standalone text node in a dialog is almost always the button.
                    val len = (node.text ?: node.contentDescription)?.toString()?.trim()?.length ?: 0
                    return len in 1..12
                }

                val button = nodes.firstOrNull { node ->
                    val text = SettingsUiStrings.normalize(node.text ?: node.contentDescription)
                    text.isNotEmpty() &&
                        strings.allowButton.any { text == it } &&  // EXACT word, not startsWith
                        !text.contains("cancel") && !text.contains("abbrechen") &&
                        isButtonLike(node)
                }

                if (button != null) {
                    Log.i(TAG, "Confirm dialog detected, pressing '%s'".format(button.text ?: button.contentDescription))
                    val ok = clickRobust(button)
                    Log.d(TAG, "Dialog confirm tap (ok=%b)".format(ok))
                    delay(1_000)
                    return
                }
            }
            delay(400)
            waited += 400
        }
        // No dialog appeared — that's fine, not every toggle raises one.
    }

    /** Taps the Wireless debugging label to open its detail screen. */
    private suspend fun openWirelessDebuggingDetail(): Boolean {
        // If "Pair device with pairing code" is already visible we're on the detail
        // screen and there is nothing to open. Check the CURRENT screen only (no scroll) —
        // we just toggled the switch, so we're already parked on the right row and any
        // extra scrolling here is the "kept scrolling afterwards" artifact.
        if (findNode(strings.pairWithCode) != null) {
            Log.i(TAG, "Already on the wireless debugging screen")
            return true
        }

        // The wireless-debugging row is already on-screen from the toggle step, so look
        // for it WITHOUT scrolling first; only scroll as a fallback if it's somehow gone.
        val row = findNode(strings.wirelessDebugging)
            ?: findNodeScrolling(strings.wirelessDebugging)
            ?: return false

        // Tap the text/label (not the switch) to navigate into the detail screen. Tapping
        // the row's label opens it; tapping the switch would toggle it back off.
        val ok = clickRobust(row)
        Log.d(TAG, "Tapped wireless debugging label to open detail (ok=%b)".format(ok))
        delay(1_500)
        return ok
    }

    private suspend fun tapPairWithCode(): Boolean {
        // On the detail screen this option is usually immediately visible; check without
        // scrolling first, then scroll only as a fallback.
        val node = findNode(strings.pairWithCode)
            ?: findNodeScrolling(strings.pairWithCode)
            ?: return false
        val ok = clickRobust(node)
        Log.d(TAG, "Tapped 'pair with code' (ok=%b)".format(ok))
        delay(1_000)
        return ok
    }

    // ------------------------------------------------ step 6: read and pair

    private class PairingCredentials(val port: Int, val code: String)

    /**
     * Polls the foreground window (and any other visible windows — the pairing dialog is
     * frequently its own window) for the six-digit code and the pairing port.
     */
    private suspend fun awaitPairingCredentials(): PairingCredentials? {
        val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
        val codeRegex = Regex("""(?<!\d)\d{6}(?!\d)""")

        var elapsed = 0L
        while (elapsed < PAIRING_CODE_TIMEOUT_MS) {
            val texts = mutableListOf<String>()

            rootInActiveWindow?.let { root ->
                collectNodes(root).forEach { n ->
                    n.text?.let { texts.add(it.toString()) }
                    n.contentDescription?.let { texts.add(it.toString()) }
                }
            }
            // The dialog can sit in a separate window from the Settings activity.
            runCatching {
                windows.forEach { w ->
                    w.root?.let { root ->
                        collectNodes(root).forEach { n ->
                            n.text?.let { texts.add(it.toString()) }
                        }
                    }
                }
            }

            var port: Int? = null
            var code: String? = null
            for (t in texts) {
                if (port == null) {
                    ipPortRegex.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { port = it }
                }
                if (code == null) {
                    codeRegex.find(t)?.value?.let { code = it }
                }
            }

            // A bare 5-digit port sometimes appears without the IP prefix.
            if (port == null) {
                val labelled = texts.firstOrNull { it.contains(":", ignoreCase = true) }
                labelled?.let {
                    Regex("""(?<!\d)\d{5}(?!\d)""").find(it)?.value?.toIntOrNull()?.let { p -> port = p }
                }
            }

            val p = port
            val c = code
            if (p != null && c != null) return PairingCredentials(p, c)

            delay(1_000)
            elapsed += 1_000
        }
        return null
    }

    private suspend fun pair(creds: PairingCredentials) {
        val key = try {
            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load ADB key", t)
            fail(getString(R.string.adb_error_key_store))
            return
        }

        AdbPairingClient("127.0.0.1", creds.port, creds.code, key).runCatching {
            start()
        }.onSuccess { paired ->
            if (paired) {
                Log.i(TAG, "Pairing succeeded")
                // Prominent, long-duration success confirmation (announce() is a short
                // toast that can flash by; pairing success deserves a clear message).
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PairingAutopilotService,
                        getString(R.string.autopilot_succeeded),
                        Toast.LENGTH_LONG
                    ).show()
                }
                withContext(Dispatchers.Main) {
                    runCatching {
                        startActivity(
                            Intent(this@PairingAutopilotService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }
                }
            } else {
                fail(getString(R.string.notification_adb_pairing_failed_title))
            }
        }.onFailure { t ->
            Log.e(TAG, "Pairing client failed", t)
            val msg = when (t) {
                is ConnectException -> getString(R.string.cannot_connect_port)
                is AdbInvalidPairingCodeException -> getString(R.string.paring_code_is_wrong)
                is AdbKeyException -> getString(R.string.adb_error_key_store)
                else -> getString(R.string.notification_adb_pairing_failed_title)
            }
            fail(msg)
        }

        // Autopilot is a one-shot: switch ourselves back off so the service isn't left
        // running with full screen-read access after the job is done.
        withContext(Dispatchers.Main) { disableSelf() }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun pause() = delay(STEP_DELAY_MS)

    /**
     * Arrests any ongoing scroll/fling momentum by planting a finger and HOLDING it
     * briefly — the same way resting a finger on a flinging list stops it dead.
     *
     * Critically this is a press-and-HOLD (touch-down, dwell ~220ms, lift), NOT a quick
     * tap. A 1ms tap-with-liftoff both fails to absorb the momentum reliably AND registers
     * as a click, which previously activated whatever row was underneath (e.g. Bug report).
     * A stationary hold cancels the fling and does not count as a click on a list row.
     *
     * The touch lands at the far-left edge, vertical centre: inside the scrollable area but
     * clear of the switches (right) and the row labels' main tap targets.
     */
    private suspend fun stopFling() {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * 0.02f).coerceAtLeast(2f)
        val y = dm.heightPixels * 0.5f

        // A single point held for a dwell period. Same start/end point = no movement, so it
        // can't be interpreted as a swipe; the 220ms duration makes it a hold, not a tap.
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 220)
            )
            .build()

        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resumeWith(Result.success(Unit))
        }
    }

    private suspend fun announce(message: String) {
        Log.i(TAG, message)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@PairingAutopilotService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fail(message: String) {
        Log.e(TAG, "FAILED: %s".format(message))
        withContext(Dispatchers.Main) {
            Toast.makeText(this@PairingAutopilotService, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Flattens a node tree into a list, depth-capped to avoid pathological UIs. */
    private fun collectNodes(
        root: AccessibilityNodeInfo?,
        depth: Int = 0,
        out: MutableList<AccessibilityNodeInfo> = mutableListOf()
    ): List<AccessibilityNodeInfo> {
        if (root == null || depth > 25) return out
        out.add(root)
        for (i in 0 until root.childCount) {
            collectNodes(root.getChild(i), depth + 1, out)
        }
        return out
    }

    private fun findNode(needles: List<String>): AccessibilityNodeInfo? {
        val all = rootInActiveWindow?.let { collectNodes(it) } ?: return null

        // Normalized text of a node: lowercased with all punctuation flattened to spaces.
        // The normalization is essential, not cosmetic — see SettingsUiStrings.normalize.
        // Without it, German "USB-Debugging" never matches the needle "usb debugging".
        fun textOf(node: AccessibilityNodeInfo): String {
            val t = SettingsUiStrings.normalize(node.text)
            val d = SettingsUiStrings.normalize(node.contentDescription)
            return if (t.isNotEmpty()) t else d
        }

        // Rows we must never match even though they CONTAIN our needle as a substring.
        //
        // The important one is the Revoke row, whose label embeds the USB debugging label
        // in every language ("Revoke USB debugging authorisations", "USB-Debugging-
        // Autorisierungen widerrufen"). Matching it by English keywords like "revoke" only
        // works in English, which is why that guard kept failing — so we primarily exclude
        // by the row's OWN resolved label, read from the Settings package in the device's
        // language. The keyword list stays as a secondary net for skins whose resource
        // didn't resolve.
        val revokeLabels = strings.revokeAdbKeys
        val differentActionWords =
            listOf("revoke", "remove", "forget", "clear", "disable", "widerrufen", "entfernen")

        fun isDifferentAction(hay: String): Boolean {
            if (revokeLabels.any { it.isNotEmpty() && hay.contains(it) }) return true
            if (hay.contains("authorisation") || hay.contains("authorization") ||
                hay.contains("autorisierung")
            ) return true
            return differentActionWords.any { hay == it || hay.startsWith("$it ") || hay.endsWith(" $it") }
        }

        // Simple CONTAINS match against the on-screen label, skipping different-action rows.
        val match = all.firstOrNull { node ->
            val hay = textOf(node)
            hay.isNotEmpty() && !isDifferentAction(hay) && needles.any { hay.contains(it) }
        }
        if (match != null) {
            Log.d(TAG, "findNode match: '%s'".format(textOf(match)))
        }
        return match
    }

    /**
     * Looks for a row, scrolling the page down between attempts. Settings rows like
     * Build number are usually well below the fold.
     */
    private suspend fun findNodeScrolling(
        needles: List<String>,
        maxScrolls: Int = MAX_SCROLLS
    ): AccessibilityNodeInfo? {
        // Check the current screen FIRST — if an OEM deep link jumped straight to the row
        // (or the detail screen), it's already visible and no scrolling is needed.
        findNode(needles)?.let {
            Log.d(TAG, "Found '%s' without scrolling".format(needles.firstOrNull()))
            return it
        }

        var lastSignature = ""
        repeat(maxScrolls) { i ->
            // Prefer a REAL swipe gesture: many OEM Settings lists silently ignore
            // ACTION_SCROLL_FORWARD (the scrollable node isn't exposed, or doesn't honor
            // the action), whereas a simulated finger drag can't be ignored. Fall back to
            // the accessibility action only if the gesture can't be dispatched.
            val moved = swipeUp() || run {
                val scrollable = rootInActiveWindow?.let { root ->
                    collectNodes(root).firstOrNull { it.isScrollable }
                }
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            }
            Log.d(TAG, "Scroll %d (moved=%b) hunting for %s".format(i + 1, moved, needles.firstOrNull()))

            // Kill any fling momentum immediately, THEN wait for the list to fully settle.
            // A swipe imparts inertia so the list keeps moving after the finger lifts; if we
            // search/tap during that, we hit whatever row slid under the point (this is what
            // caused a mis-tap onto Battery saver). stopFling() arrests the scroll first.
            stopFling()
            delay(700)

            findNode(needles)?.let {
                Log.d(TAG, "Found '%s' after %d scroll(s)".format(needles.firstOrNull(), i + 1))
                return it
            }

            // Detect end-of-list: if the visible text hasn't changed since the last swipe,
            // we've hit the bottom and further scrolling is pointless.
            val signature = screenTextSignature()
            if (signature == lastSignature) {
                Log.d(TAG, "List didn't change after scroll %d, assuming end of list".format(i + 1))
                Log.w(TAG, "Row not found (end of list): %s".format(needles.firstOrNull()))
                return null
            }
            lastSignature = signature
        }
        Log.w(TAG, "Row not found (max scrolls): %s".format(needles.firstOrNull()))
        return null
    }

    /**
     * Swipes up (content moves up, i.e. scrolls the list DOWN) with a real touch gesture.
     * Swipes within the bounds of the largest scrollable node when one is exposed, else
     * across the middle of the screen. Returns whether the gesture was dispatched.
     */
    private suspend fun swipeUp(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Prefer the scrollable region's bounds so we don't start the swipe on a toolbar
        // or the nav bar; fall back to screen metrics.
        val scrollable = collectNodes(root).firstOrNull { it.isScrollable }
        val area = android.graphics.Rect()
        if (scrollable != null) {
            scrollable.getBoundsInScreen(area)
        }
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels
        if (area.width() <= 0 || area.height() <= 0) {
            area.set(0, 0, dm.widthPixels, screenH)
        }

        // SAFE ZONES — keep the whole swipe clear of the system gesture regions:
        //  - Bottom: the gesture-nav pill / back-swipe area lives in the bottom ~10% of the
        //    SCREEN. Starting a drag there triggers Home/Recents instead of scrolling.
        //  - Top: the status bar / quick-settings pulldown lives in the top ~8% of the
        //    screen. Ending a drag there can pull the shade down.
        // We clamp against SCREEN coordinates (gesture zones are screen-relative), not just
        // the list bounds, and add a fixed dp buffer on top of the percentage so it holds
        // on very short or very tall screens.
        val fixedBufferPx = (48 * dm.density)
        val bottomSafe = screenH - maxOf(screenH * 0.10f, fixedBufferPx)
        val topSafe = maxOf(screenH * 0.08f, fixedBufferPx)

        val x = area.centerX().toFloat()

        // Desired start/end within the list, then clamped into the safe band.
        var startY = area.top + area.height() * 0.70f
        var endY = area.top + area.height() * 0.30f
        startY = startY.coerceAtMost(bottomSafe)
        endY = endY.coerceAtLeast(topSafe)

        // If clamping collapsed the swipe (tiny scroll area near screen edges), fall back
        // to a safe mid-screen drag so we still move the list a meaningful distance.
        if (startY - endY < screenH * 0.15f) {
            startY = bottomSafe
            endY = topSafe
        }

        Log.d(TAG, "swipeUp x=%.0f from y=%.0f to y=%.0f (safe %.0f..%.0f, screenH=%d)"
            .format(x, startY, endY, topSafe, bottomSafe, screenH))

        val path = android.graphics.Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                // ~300ms drag: fast enough to fling a little, slow enough to be reliable.
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)
            )
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resumeWith(Result.success(false))
        }
    }

    /** A cheap fingerprint of the currently visible text, to detect end-of-list. */
    private fun screenTextSignature(): String {
        val root = rootInActiveWindow ?: return ""
        return collectNodes(root)
            .mapNotNull { it.text?.toString() }
            .joinToString("|")
    }

    /**
     * Text nodes in Settings are almost never clickable themselves — the click target is
     * an ancestor row. Walks up until it finds something clickable.
     */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked %s (hops=%d, ok=%b)".format(current.className, hops, ok))
                if (ok) return true
            }
            current = current.parent
            hops++
        }
        Log.w(TAG, "No clickable ancestor found")
        return false
    }

    /**
     * Tries every reasonable way to activate a node, for cases where a plain
     * [clickNodeOrAncestor] no-ops — most notably the Settings search bar, which is often
     * a non-clickable container whose real target is a descendant, or which only responds
     * to an actual touch.
     *
     * Order: the node itself -> a clickable descendant -> a clickable ancestor ->
     * a synthesized tap at the node's screen coordinates (needs canPerformGestures, which
     * the autopilot's accessibility config declares).
     */
    private suspend fun clickRobust(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. The node itself.
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "clickRobust: clicked node directly")
            return true
        }

        // 2. A clickable descendant (the direction clickNodeOrAncestor never checks).
        val descendant = collectNodes(node).firstOrNull { it.isClickable }
        if (descendant != null && descendant.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "clickRobust: clicked descendant %s".format(descendant.className))
            return true
        }

        // 3. A clickable ancestor.
        if (clickNodeOrAncestor(node)) {
            Log.d(TAG, "clickRobust: clicked ancestor")
            return true
        }

        // 4. Last resort: tap the node's centre with a gesture. Works even when nothing in
        //    the hierarchy is marked clickable, because it simulates a real finger touch.
        //    Refresh first so the bounds are current — stale bounds here were tapping blind
        //    coordinates that had drifted onto the wrong row (Battery saver).
        node.refresh()
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            Log.d(TAG, "clickRobust: gesture-tapping at (%d,%d)".format(rect.centerX(), rect.centerY()))
            return gestureTap(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        Log.w(TAG, "clickRobust: all strategies failed")
        return false
    }

    /** Dispatches a short tap gesture at the given screen coordinates. */
    private suspend fun gestureTap(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
            )
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resumeWith(Result.success(false))
        }
    }
}
