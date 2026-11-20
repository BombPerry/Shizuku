package moe.shizuku.manager.adb

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log

/**
 * Resolves the on-screen labels of the system Settings app so the pairing autopilot can
 * find them regardless of the device language.
 *
 * Matching Settings UI by hardcoded English text breaks the moment the user runs a
 * non-English locale. Instead we load `com.android.settings`' own resources and ask it
 * for the very strings it renders. If a ROM has renamed or removed a string resource we
 * fall back to a small list of known literals.
 *
 * All matching is done case-insensitively, on a "contains" basis, and against text that
 * has been run through [normalize] — see the note there on why punctuation must be
 * flattened before comparing.
 */
object SettingsUiStrings {

    private const val SETTINGS_PKG = "com.android.settings"
    private const val TAG = "PairAutopilot/Strings"

    private var cached: Resolved? = null

    class Resolved(
        val buildNumber: List<String>,
        val softwareInfo: List<String>,
        val wirelessDebugging: List<String>,
        val pairWithCode: List<String>,
        val allowButton: List<String>,
        /**
         * The "Revoke USB debugging authorisations" row. NOT a navigation target — this is
         * resolved purely so it can be EXCLUDED from matching. Its label contains the USB
         * debugging label as a substring in every language, so without an explicit
         * exclusion a "contains" search finds it first and taps it.
         */
        val revokeAdbKeys: List<String>,
        /**
         * The "Disable adb authorisation timeout" row (AOSP key adb_authorization_timeout),
         * which sits directly BELOW Wireless debugging in Developer options. Resolved as a
         * lower ANCHOR: on skins where the Wireless debugging row's own title node isn't
         * exposed to accessibility, we locate the Revoke row (above) and this row (below) and
         * tap the gap between them, where Wireless debugging must be.
         */
        val adbTimeout: List<String>,
    )

    /**
     * AOSP string resource names, each with CANDIDATE names to try in order, paired with
     * literal fallbacks.
     *
     * The resource names here are the ones AOSP's own `res/xml/development_settings.xml`
     * references, verified against the AOSP source rather than guessed:
     *
     *   android:key="enable_adb"          android:title="@string/enable_adb"
     *   android:key="clear_adb_keys"      android:title="@string/clear_adb_keys"
     *   android:key="toggle_adb_wireless" android:title="@string/enable_adb_wireless"
     *
     * This matters more than it looks: an unresolvable name fails SILENTLY (getIdentifier
     * returns 0), leaving only the English literal fallbacks. On a non-English device that
     * means every lookup quietly degrades to matching English text against a localized UI,
     * which never matches — the row is simply never found and the autopilot scrolls past
     * it. Multiple candidates are tried so OEM skins that kept an older/renamed resource
     * still resolve.
     */
    private val SPEC = mapOf(
        "buildNumber" to (listOf("build_number") to
            listOf("build number", "build no", "buildnummer")),
        "softwareInfo" to (listOf("software_information", "firmware_version") to
            listOf("software information", "software info", "softwareinformationen")),
        "wirelessDebugging" to (listOf("enable_adb_wireless", "adb_wireless_settings") to
            listOf("wireless debugging", "wireless adb", "debugging over wi fi",
                "drahtloses debugging")),
        "revokeAdbKeys" to (listOf("clear_adb_keys") to
            listOf("revoke usb debugging authorisations", "revoke usb debugging authorizations",
                "revoke usb debugging", "usb debugging autorisierungen widerrufen",
                "usb debugging autorisierung widerrufen")),
        "pairWithCode" to (listOf("adb_pair_method_code_title") to
            listOf("pair device with pairing code", "pairing code", "kopplungscode")),
        "allowButton" to (listOf("allow") to listOf("allow", "ok", "yes", "zulassen", "ja")),
        "adbTimeout" to (listOf("adb_authorization_timeout_title", "adb_authorization_timeout") to
            listOf("disable adb authorisation timeout", "disable adb authorization timeout",
                "adb authorisation timeout", "adb authorization timeout")),
    )

    fun get(context: Context): Resolved {
        cached?.let { return it }

        val res: Resources? = try {
            context.packageManager.getResourcesForApplication(SETTINGS_PKG)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Settings package not resolvable, using literal fallbacks only")
            null
        }

        fun resolve(key: String): List<String> {
            val (candidates, fallbacks) = SPEC.getValue(key)
            val out = LinkedHashSet<String>()
            var resolvedFrom: String? = null
            if (res != null) {
                for (name in candidates) {
                    val id = res.getIdentifier(name, "string", SETTINGS_PKG)
                    if (id == 0) continue
                    runCatching { res.getString(id) }
                        .onSuccess {
                            if (it.isNotBlank()) {
                                out.add(normalize(it))
                                resolvedFrom = name
                            }
                        }
                        .onFailure { Log.w(TAG, "Could not read string '$name'") }
                    if (out.isNotEmpty()) break
                }
            }
            // Loudly flag the silent-degradation case: no resource resolved means we are
            // about to match English literals against a possibly-localized UI.
            if (resolvedFrom == null) {
                Log.w(TAG, "%s: NO Settings resource resolved (tried %s) - falling back to literals %s"
                    .format(key, candidates.joinToString(", "), fallbacks))
            } else {
                Log.i(TAG, "%s: resolved from R.string.%s -> '%s'".format(key, resolvedFrom, out.first()))
            }
            fallbacks.forEach { out.add(normalize(it)) }
            return out.toList()
        }

        val resolved = Resolved(
            buildNumber = resolve("buildNumber"),
            softwareInfo = resolve("softwareInfo"),
            wirelessDebugging = resolve("wirelessDebugging"),
            pairWithCode = resolve("pairWithCode"),
            allowButton = resolve("allowButton"),
            revokeAdbKeys = resolve("revokeAdbKeys"),
            adbTimeout = resolve("adbTimeout"),
        )
        cached = resolved
        return resolved
    }

    /**
     * Lowercases and flattens every run of non-alphanumeric characters to a single space.
     *
     * This is what makes matching survive punctuation differences between locales and OEM
     * skins. German Settings renders the USB debugging row as "USB-Debugging"; a plain
     * lowercase "contains" test against the needle "usb debugging" FAILS on that hyphen.
     * After normalize() both sides read "usb debugging" and match. Same story for
     * "Wi-Fi"/"WiFi" and for skins that use en-dashes or non-breaking spaces.
     */
    fun normalize(text: CharSequence?): String =
        text?.toString()
            ?.lowercase()
            ?.map { if (it.isLetterOrDigit()) it else ' ' }
            ?.joinToString("")
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()

    /** True if [candidate] contains any of [needles], compared after normalization. */
    fun matches(candidate: CharSequence?, needles: List<String>): Boolean {
        val text = normalize(candidate)
        if (text.isEmpty()) return false
        return needles.any { text.contains(normalize(it)) }
    }
}
