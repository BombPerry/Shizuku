package moe.shizuku.manager.adb

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException

/**
 * Measures which ports the kernel hands out to listening sockets on this device, so the boot
 * start can scan the wireless-debugging port range first ([AdbPortScanner.buildOrder]).
 *
 * adbd's TLS server binds port 0 with SO_REUSEADDR and lets the kernel choose. This does the
 * same thing [SAMPLES] times (bind, read the port, close), which needs neither Wi-Fi nor
 * wireless debugging:
 *
 *  - with SO_REUSEADDR: the kernel tries the lower half of ip_local_port_range, odd ports only
 *    (inet_csk_find_open_port, Linux 4.6+). This is the band adbd's port comes from.
 *  - without SO_REUSEADDR: odd ports from the whole range, which shows the range itself. On some
 *    devices /proc/sys/net/ipv4/ip_local_port_range can't be read, even from adb shell.
 *
 * With a million samples every port of the band is hit hundreds of times, so the lowest and
 * highest sample are the real bounds and no margin is added.
 *
 * Whether the boot scan skips even ports is a separate switch ([isOddOnlyScan]): a measurement
 * sets it to what it detected, and the user can change it in Settings.
 *
 * Everything is kept in device-encrypted storage so the boot start can read it before unlock.
 */
object AdbPortRange {

    /**
     * @param listenLow lowest port of the adb band
     * @param listenHigh highest port of the adb band
     * @param oddShare share of odd ports in the band measurement, 0..1
     * @param fullLow lowest port of the whole ephemeral range
     * @param fullHigh highest port of the whole ephemeral range
     * @param samples samples per measurement
     */
    data class Calibration(
        val listenLow: Int,
        val listenHigh: Int,
        val oddShare: Double,
        val fullLow: Int,
        val fullHigh: Int,
        val samples: Int,
    ) {
        /** True if the kernel gave (nearly) only odd ports, as Linux 4.6+ does. */
        val detectedOddOnly: Boolean get() = oddShare >= ODD_SHARE_MIN
    }

    const val SAMPLES = 1_000_000

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    private const val PREFS = "adb_port_range"
    private const val KEY_LISTEN_LOW = "listen_low"
    private const val KEY_LISTEN_HIGH = "listen_high"
    private const val KEY_ODD_SHARE = "odd_share"
    private const val KEY_FULL_LOW = "full_low"
    private const val KEY_FULL_HIGH = "full_high"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_ODD_ONLY_SCAN = "odd_only_scan"

    // Share of odd ports needed to treat the band as odd-only (it is 100% on Linux 4.6+).
    private const val ODD_SHARE_MIN = 0.999

    /**
     * Runs both measurements. Blocking (a few seconds up to about a minute, depending on the
     * device); call it off the main thread.
     *
     * @param onProgress called with 0..100 as the measurement advances
     * @param shouldStop polled regularly; when it returns true, a [CancellationException] is thrown
     */
    fun measure(
        samples: Int = SAMPLES,
        onProgress: (Int) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Calibration {
        val band = Sampler(count = samples, reuseAddress = true, progressBase = 0, onProgress = onProgress, shouldStop = shouldStop).run()
        val full = Sampler(count = samples, reuseAddress = false, progressBase = 50, onProgress = onProgress, shouldStop = shouldStop).run()
        onProgress(100)
        return Calibration(
            listenLow = band.low,
            listenHigh = band.high,
            oddShare = band.odd.toDouble() / samples,
            fullLow = minOf(full.low, band.low),
            fullHigh = maxOf(full.high, band.high),
            samples = samples,
        )
    }

    private class Stats(val low: Int, val high: Int, val odd: Int)

    /** Binds port 0 [count] times and keeps only min, max and the odd count (no big arrays). */
    private class Sampler(
        private val count: Int,
        private val reuseAddress: Boolean,
        private val progressBase: Int,
        private val onProgress: (Int) -> Unit,
        private val shouldStop: () -> Boolean,
    ) {
        fun run(): Stats {
            val ipv6 = ipv6Available()
            val any = InetAddress.getByAddress(ByteArray(if (ipv6) 16 else 4))
            val family = if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET
            val step = maxOf(1, count / 50) // one progress update per percent of the total
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            var odd = 0
            for (i in 0 until count) {
                if (i % step == 0) {
                    if (shouldStop()) throw CancellationException("port range measurement cancelled")
                    onProgress(progressBase + i / step)
                }
                val fd = Os.socket(family, OsConstants.SOCK_STREAM, 0)
                val port = try {
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, if (reuseAddress) 1 else 0)
                    Os.bind(fd, any, 0)
                    (Os.getsockname(fd) as InetSocketAddress).port
                } finally {
                    Os.close(fd)
                }
                if (port < low) low = port
                if (port > high) high = port
                if (port % 2 == 1) odd++
            }
            return Stats(low, high, odd)
        }
    }

    private fun ipv6Available(): Boolean = try {
        Os.close(Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0))
        true
    } catch (e: ErrnoException) {
        false
    }

    /** How many of [ports] lie inside the adb band. */
    fun countInside(calibration: Calibration, ports: List<Int>): Int =
        ports.count { it in calibration.listenLow..calibration.listenHigh }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves [c] and sets the odd-only switch to what was detected. */
    fun save(context: Context, c: Calibration) {
        prefs(context).edit()
            .putInt(KEY_LISTEN_LOW, c.listenLow)
            .putInt(KEY_LISTEN_HIGH, c.listenHigh)
            .putFloat(KEY_ODD_SHARE, c.oddShare.toFloat())
            .putInt(KEY_FULL_LOW, c.fullLow)
            .putInt(KEY_FULL_HIGH, c.fullHigh)
            .putInt(KEY_SAMPLES, c.samples)
            .putBoolean(KEY_ODD_ONLY_SCAN, c.detectedOddOnly)
            .commit()
    }

    /** The saved measurement, or null if there is none. Never throws. */
    fun load(context: Context): Calibration? = try {
        val p = prefs(context)
        if (!p.contains(KEY_LISTEN_LOW)) null
        else Calibration(
            listenLow = p.getInt(KEY_LISTEN_LOW, 0),
            listenHigh = p.getInt(KEY_LISTEN_HIGH, 0),
            oddShare = p.getFloat(KEY_ODD_SHARE, 0f).toDouble(),
            fullLow = p.getInt(KEY_FULL_LOW, 0),
            fullHigh = p.getInt(KEY_FULL_HIGH, 0),
            samples = p.getInt(KEY_SAMPLES, 0),
        ).takeIf { it.listenLow in MIN_PORT..it.listenHigh && it.fullLow <= it.fullHigh }
    } catch (e: Exception) {
        null
    }

    /**
     * Whether the boot scan skips even ports entirely. Defaults to off until a measurement has
     * detected odd-only ports (older kernels hand out even ports too). mDNS still finds an even
     * port if the scan skips it.
     */
    fun isOddOnlyScan(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_ODD_ONLY_SCAN, false)
    } catch (e: Exception) {
        false
    }

    fun setOddOnlyScan(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ODD_ONLY_SCAN, value).commit()
    }

    /** Deletes the measurement and turns the odd-only switch off. */
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
