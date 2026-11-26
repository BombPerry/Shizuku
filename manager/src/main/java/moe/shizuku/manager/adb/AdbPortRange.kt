package moe.shizuku.manager.adb

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException

/**
 * Finds out which ports adbd's wireless-debugging server can get on this device, so the boot
 * start can scan that band first ([AdbPortScanner.buildOrder]).
 *
 * adbd binds port 0 with SO_REUSEADDR and lets the kernel choose. The kernel then takes a port
 * from the lower half of ip_local_port_range, with the opposite parity of its lowest port, so
 * odd ports for the usual even lower bound (inet_csk_find_open_port, Linux 4.6+).
 *
 * [measure] works in this order:
 *  1. Read ip_local_port_range: from /proc/sys (often not allowed for apps), otherwise with
 *     `sysctl` through the running Shizuku server, which runs as the adb shell (or root).
 *     If that works, the band is computed with the kernel's formula, and a short sample of
 *     [CHECK_SAMPLES] binds confirms that the kernel really behaves like that.
 *  2. Otherwise sample: bind port 0 again and again, with SO_REUSEADDR (the band) and without
 *     it (the whole range), and stop once the lowest and highest port haven't changed for a
 *     while ([stableWindow]), at most [MAX_SAMPLES] per round.
 *
 * Whether the boot scan skips even ports is a separate switch ([isOddOnlyScan]): a measurement
 * sets it to what it detected, and the user can change it in Settings.
 *
 * Everything is kept in device-encrypted storage so the boot start can read it before unlock.
 */
object AdbPortRange {

    /** Where the whole port range came from. */
    enum class Source { FILE, SYSCTL, SAMPLING }

    /**
     * @param listenLow lowest port of the adb band
     * @param listenHigh highest port of the adb band
     * @param oddShare share of odd ports among the band samples, 0..1
     * @param fullLow lowest port of the whole ephemeral range
     * @param fullHigh highest port of the whole ephemeral range
     * @param samples binds made in total
     * @param source how the whole range was found
     */
    data class Calibration(
        val listenLow: Int,
        val listenHigh: Int,
        val oddShare: Double,
        val fullLow: Int,
        val fullHigh: Int,
        val samples: Int,
        val source: Source,
    ) {
        /** True if the kernel gave (nearly) only odd ports, as Linux 4.6+ does with an even lower bound. */
        val detectedOddOnly: Boolean get() = oddShare >= ODD_SHARE_MIN
    }

    /** Upper limit per sampling round. */
    const val MAX_SAMPLES = 1_000_000

    /** A sampling round stops when min and max haven't changed for this many binds, or more for wide ranges. */
    const val MIN_STABLE_SAMPLES = 50_000

    /**
     * For wide ranges the window grows to this many times the number of candidate ports, so the
     * chance of missing the lowest or highest port stays around e^-20 per bound (e^-(window/ports)).
     */
    private const val STABLE_PER_PORT = 20

    /** Binds used to check the computed band when the range could be read. */
    const val CHECK_SAMPLES = 20_000

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    private const val RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"

    private const val PREFS = "adb_port_range"
    private const val KEY_LISTEN_LOW = "listen_low"
    private const val KEY_LISTEN_HIGH = "listen_high"
    private const val KEY_ODD_SHARE = "odd_share"
    private const val KEY_FULL_LOW = "full_low"
    private const val KEY_FULL_HIGH = "full_high"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_SOURCE = "source"
    private const val KEY_ODD_ONLY_SCAN = "odd_only_scan"

    // Share of odd ports needed to treat the band as odd-only (it is 100% on Linux 4.6+).
    private const val ODD_SHARE_MIN = 0.999

    /**
     * Blocking; call it off the main thread. Reading the range takes well under a second;
     * sampling a few seconds, up to about a minute on wide ranges or slow devices.
     *
     * @param onProgress called with the number of binds made so far
     * @param shouldStop polled regularly; when it returns true, a [CancellationException] is thrown
     */
    fun measure(
        onProgress: (Int) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Calibration {
        val read = readRangeFromFile()?.let { it to Source.FILE }
            ?: readRangeWithSysctl()?.let { it to Source.SYSCTL }

        if (read != null) {
            val (range, source) = read
            val band = kernelBand(range.first, range.last)
            val check = Sampler(reuseAddress = true, onProgress = onProgress, shouldStop = shouldStop)
                .run(maxSamples = CHECK_SAMPLES, stopWhenStable = false)
            // Use the computed band only if every sample fell inside it; otherwise the kernel
            // works differently here, so trust the samples.
            val fits = check.low >= band.first && check.high <= band.last
            return Calibration(
                listenLow = if (fits) band.first else check.low,
                listenHigh = if (fits) band.last else check.high,
                oddShare = check.odd.toDouble() / check.count,
                fullLow = minOf(range.first, check.low),
                fullHigh = maxOf(range.last, check.high),
                samples = check.count,
                source = source,
            )
        }

        val band = Sampler(reuseAddress = true, onProgress = onProgress, shouldStop = shouldStop)
            .run(maxSamples = MAX_SAMPLES, stopWhenStable = true)
        val full = Sampler(reuseAddress = false, progressOffset = band.count, onProgress = onProgress, shouldStop = shouldStop)
            .run(maxSamples = MAX_SAMPLES, stopWhenStable = true)
        return Calibration(
            listenLow = band.low,
            listenHigh = band.high,
            oddShare = band.odd.toDouble() / band.count,
            fullLow = minOf(full.low, band.low),
            fullHigh = maxOf(full.high, band.high),
            samples = band.count + full.count,
            source = Source.SAMPLING,
        )
    }

    /**
     * The ports a SO_REUSEADDR bind(0) can get, as inet_csk_find_open_port computes them:
     * high++; half = low + (((high - low) >> 2) << 1); ports low..half-1.
     */
    fun kernelBand(low: Int, high: Int): IntRange {
        val end = high + 1
        val half = low + (((end - low) shr 2) shl 1)
        return low until half
    }

    // ---- reading ip_local_port_range ----

    /** "37000\t50000" or "net.ipv4.ip_local_port_range = 37000\t50000" -> 37000..50000 */
    fun parseRange(text: String): IntRange? {
        val numbers = Regex("\\d+").findAll(text.substringAfter('=')).map { it.value.toInt() }.toList()
        if (numbers.size < 2) return null
        val (low, high) = numbers
        return if (low in MIN_PORT..high && high <= MAX_PORT) low..high else null
    }

    /** Works on some devices; Android's app sandbox often blocks it. */
    private fun readRangeFromFile(): IntRange? = try {
        parseRange(File(RANGE_FILE).readText())
    } catch (e: Exception) {
        null
    }

    /** Runs `sysctl` through the Shizuku server (adb shell or root). Null if the server isn't running. */
    private fun readRangeWithSysctl(): IntRange? = try {
        runOnServer(arrayOf("sysctl", "net.ipv4.ip_local_port_range"))?.let { parseRange(it) }
            ?: runOnServer(arrayOf("cat", RANGE_FILE))?.let { parseRange(it) }
    } catch (e: Exception) {
        null
    }

    /** Runs [cmd] as the Shizuku server's user and returns its output, or null if that fails. */
    private fun runOnServer(cmd: Array<String>): String? {
        val binder = Shizuku.getBinder() ?: return null
        if (!binder.pingBinder()) return null
        val process = IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null) ?: return null
        return try {
            process.outputStream?.close()
            process.errorStream?.close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use {
                it.bufferedReader().readText()
            }
            if (process.waitFor() == 0) output else null
        } finally {
            runCatching { process.destroy() }
        }
    }

    // ---- sampling ----

    private class Stats(val low: Int, val high: Int, val odd: Int, val count: Int)

    /** Binds port 0 repeatedly and keeps only min, max and the odd count (no big arrays). */
    private class Sampler(
        private val reuseAddress: Boolean,
        private val progressOffset: Int = 0,
        private val onProgress: (Int) -> Unit,
        private val shouldStop: () -> Boolean,
    ) {
        fun run(maxSamples: Int, stopWhenStable: Boolean): Stats {
            val ipv6 = ipv6Available()
            val any = InetAddress.getByAddress(ByteArray(if (ipv6) 16 else 4))
            val family = if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            var odd = 0
            var count = 0
            var lastChange = 0
            while (count < maxSamples) {
                if (count % 5_000 == 0) {
                    if (shouldStop()) throw CancellationException("port range measurement cancelled")
                    onProgress(progressOffset + count)
                }
                val fd = Os.socket(family, OsConstants.SOCK_STREAM, 0)
                val port = try {
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, if (reuseAddress) 1 else 0)
                    Os.bind(fd, any, 0)
                    (Os.getsockname(fd) as InetSocketAddress).port
                } finally {
                    Os.close(fd)
                }
                count++
                if (port < low) { low = port; lastChange = count }
                if (port > high) { high = port; lastChange = count }
                if (port % 2 == 1) odd++
                if (stopWhenStable && count - lastChange >= stableWindow(low, high, odd, count)) break
            }
            onProgress(progressOffset + count)
            return Stats(low, high, odd, count)
        }
    }

    /**
     * How long min and max must stay unchanged: [MIN_STABLE_SAMPLES], or [STABLE_PER_PORT] times
     * the number of candidate ports seen so far if that is more (half as many if all were odd).
     */
    private fun stableWindow(low: Int, high: Int, odd: Int, count: Int): Int {
        val span = (high - low + 1).toLong()
        val candidates = if (odd == count || odd == 0) span / 2 else span
        return maxOf(MIN_STABLE_SAMPLES.toLong(), candidates * STABLE_PER_PORT).coerceAtMost(MAX_SAMPLES.toLong()).toInt()
    }

    private fun ipv6Available(): Boolean = try {
        Os.close(Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0))
        true
    } catch (e: ErrnoException) {
        false
    }

    // ---- storage ----

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
            .putString(KEY_SOURCE, c.source.name)
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
            source = runCatching { Source.valueOf(p.getString(KEY_SOURCE, null)!!) }.getOrDefault(Source.SAMPLING),
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
