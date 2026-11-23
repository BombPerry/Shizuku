package moe.shizuku.manager.adb

import android.content.Context
import android.os.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the wireless-debugging (adbd TLS) port without mDNS. adbd listens within milliseconds of
 * `adb_wifi_enabled=1`, but its mDNS registration can fail right after boot (error -65563) and is
 * visible to discovery only ~1 s later; `service.adb.tls.port` is not readable by apps and logcat
 * is not available before unlock.
 *
 * The TLS server listens on an ephemeral port on all interfaces. Every open loopback port in
 * 32768..60999 is probed with an adb CNXN packet; adbd answers STLS (AUTH / CNXN for a plain adbd).
 *
 * Scan order ([buildOrder]): a listening socket bound to port 0 gets an ODD port, and with
 * SO_REUSEADDR the kernel tries the LOWER HALF of ip_local_port_range first
 * (inet_csk_find_open_port: `offset |= 1U`, `attempt_half`). So the scan goes:
 *   1. the adb band measured in Settings ([AdbPortRange]), if any,
 *   2. odd ports around the ports found on previous boots ([AdbPortHistory]), which adapts to
 *      OEMs that change ip_local_port_range (not readable by apps on some devices),
 *   3. odd ports in the lower half of the default range (32769..46883),
 *   4. the remaining odd ports, then the even ones (left out when the odd-only switch is on).
 * Every port is still scanned in each pass; only the order changes.
 *
 * Port scanner by Dimlos (Dimzuku, github.com/Dimlos-jp/Dimzuku); scan order, port history and
 * the measured range ([AdbPortRange]) added in this fork.
 *
 * Call [start] right after the toggle and [stop] when done. The first port found is reported once
 * through [onPort].
 */
class AdbPortScanner(
    private val log: (String) -> Unit,
    private val order: IntArray = linearOrder(),
    private val onPort: (Int) -> Unit,
) {

    @Volatile private var generation = 0
    @Volatile private var hit = false

    /** Scans repeatedly until [stop] or a hit. */
    fun start() {
        val gen = ++generation
        hit = false
        Thread({ scanLoop(gen) }, "adb-scan").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
    }

    private fun scanLoop(gen: Int) {
        val t0 = SystemClock.elapsedRealtime()
        val pool = Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "adb-scan-w").apply { isDaemon = true }
        }
        try {
            var pass = 0
            while (gen == generation && !hit) {
                pass++
                val tp = SystemClock.elapsedRealtime()
                val next = AtomicInteger(0)
                val open = AtomicInteger(0)
                val tasks = (1..THREADS).map {
                    Callable {
                        while (gen == generation && !hit) {
                            val i = next.getAndIncrement()
                            if (i >= order.size) break
                            val p = order[i]
                            if (!isOpen(p)) continue
                            open.incrementAndGet()
                            val tOpen = SystemClock.elapsedRealtime()
                            val ok = probe(p)
                            log("scan: open port $p at +${tOpen - t0}ms, probe ${SystemClock.elapsedRealtime() - tOpen}ms ok=$ok")
                            if (ok && gen == generation && !hit) {
                                hit = true
                                log("scan: port $p (+${SystemClock.elapsedRealtime() - t0}ms after scan start, pass $pass, position $i of ${order.size} in scan order)")
                                onPort(p)
                                break
                            }
                        }
                    }
                }
                pool.invokeAll(tasks)
                if (pass == 1 || hit) {
                    log("scan: pass $pass took ${SystemClock.elapsedRealtime() - tp}ms, open ports in range=${open.get()}")
                }
                if (!hit && gen == generation) Thread.sleep(PASS_GAP_MS)
            }
        } catch (e: Throwable) {
            log("scan: failed: $e")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** Sends an adb CNXN; an adbd answers with STLS / AUTH / CNXN. */
    private fun probe(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
            val data = "host::\u0000".toByteArray()
            val cnxn = 0x4e584e43
            val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(cnxn).putInt(0x01000001).putInt(256 * 1024).putInt(data.size)
            buf.putInt(data.sumOf { it.toInt() and 0xff }).putInt(cnxn.inv())
            buf.put(data)
            s.getOutputStream().apply { write(buf.array()); flush() }
            val head = ByteArray(4)
            var n = 0
            val ins = s.getInputStream()
            while (n < 4) {
                val r = ins.read(head, n, 4 - n)
                if (r < 0) break
                n += r
            }
            n == 4 && String(head, Charsets.ISO_8859_1) in setOf("STLS", "AUTH", "CNXN")
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val PORT_MIN = 32768
        const val PORT_MAX = 60999

        // Margin around the ports seen on previous boots, for the first scan tier.
        private const val LEARNED_MARGIN = 500

        /** The original order: PORT_MIN..PORT_MAX. */
        fun linearOrder(): IntArray = (PORT_MIN..PORT_MAX).toList().toIntArray()

        /**
         * Scan order, first to last (see class doc):
         *   measured adb band ([AdbPortRange], from Settings) -> band around previously found
         *   ports -> rest of the measured range (odd) -> odd lower half of the default range ->
         *   other odd default ports -> even default ports -> even ports of the measured range.
         * With [oddOnly] (Settings switch), even ports are left out entirely, so a pass is about
         * half as long; mDNS still reports an even port if one is ever used.
         * Every port appears once; ports outside 1024..65535 are ignored.
         */
        fun buildOrder(
            history: List<Int>,
            calibration: AdbPortRange.Calibration? = null,
            oddOnly: Boolean = false,
        ): IntArray {
            val seen = BooleanArray(AdbPortRange.MAX_PORT + 1)
            val out = IntArray(AdbPortRange.MAX_PORT + 1)
            var n = 0
            fun add(from: Int, to: Int, step: Int) {
                var p = from
                while (p <= to) {
                    if (p in AdbPortRange.MIN_PORT..AdbPortRange.MAX_PORT && !seen[p]) {
                        seen[p] = true
                        out[n++] = p
                    }
                    p += step
                }
            }
            fun odd(x: Int) = if (x % 2 == 0) x + 1 else x
            // Odd ports from [from] to [to]; with oddOnly off, the band itself takes all ports.
            fun addOdd(from: Int, to: Int) = add(odd(from), to, 2)

            if (calibration != null) {
                if (oddOnly || calibration.detectedOddOnly) addOdd(calibration.listenLow, calibration.listenHigh)
                if (!oddOnly) add(calibration.listenLow, calibration.listenHigh, 1)
            }
            val known = history.filter { it in AdbPortRange.MIN_PORT..AdbPortRange.MAX_PORT }
            if (known.isNotEmpty()) {
                addOdd(known.minOf { it } - LEARNED_MARGIN, known.maxOf { it } + LEARNED_MARGIN)
            }
            if (calibration != null) addOdd(calibration.fullLow, calibration.fullHigh)
            // Lower half as computed by inet_csk_find_open_port: low + (((high - low) >> 2) << 1)
            val half = PORT_MIN + (((PORT_MAX + 1 - PORT_MIN) shr 2) shl 1)
            addOdd(PORT_MIN, half - 1)
            addOdd(half, PORT_MAX)
            if (!oddOnly) {
                add(PORT_MIN, PORT_MAX, 1) // even ports; odd ones are already in
                if (calibration != null) add(calibration.fullLow, calibration.fullHigh, 1)
            }
            return out.copyOf(n)
        }

        /** Short description for the boot log. */
        fun describe(
            order: IntArray,
            history: List<Int>,
            calibration: AdbPortRange.Calibration? = null,
            oddOnly: Boolean = false,
        ): String {
            val parts = mutableListOf<String>()
            if (calibration != null) {
                parts += "measured band ${calibration.listenLow}..${calibration.listenHigh}" +
                        " of range ${calibration.fullLow}..${calibration.fullHigh}"
            }
            val known = history.filter { it in AdbPortRange.MIN_PORT..AdbPortRange.MAX_PORT }
            if (known.isNotEmpty()) {
                parts += "history band ${known.minOf { it } - LEARNED_MARGIN}..${known.maxOf { it } + LEARNED_MARGIN}, history=$known"
            }
            if (parts.isEmpty()) parts += "no measurement or history yet, odd lower half first"
            return parts.joinToString(", then ") +
                    (if (oddOnly) ", odd ports only" else ", odd ports first") +
                    ", ${order.size} ports"
        }

        private const val THREADS = 64
        private const val CONNECT_TIMEOUT_MS = 300
        private const val PROBE_TIMEOUT_MS = 600
        private const val PASS_GAP_MS = 50L
    }
}

/**
 * Wireless-debugging ports found on previous boots, kept in device-encrypted storage so it can be
 * read before first unlock. Used by [AdbPortScanner.buildOrder].
 */
object AdbPortHistory {
    private const val PREFS = "adb_port_history"
    private const val KEY = "ports"
    private const val MAX_ENTRIES = 10

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Int> = try {
        prefs(context).getString(KEY, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
    } catch (e: Exception) {
        emptyList()
    }

    fun record(context: Context, port: Int) {
        try {
            val list = (load(context) + port).takeLast(MAX_ENTRIES)
            prefs(context).edit().putString(KEY, list.joinToString(",")).commit()
        } catch (e: Exception) {
            // History is only an optimisation.
        }
    }
}
