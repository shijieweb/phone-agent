package net.de5.phoneagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * A location fix reduced to plain data, so that [locationJson] can be built
 * (and tested) without touching `android.location.Location`.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val acc: Double,
    /** Epoch **seconds** (the relay works in seconds). */
    val ts: Long,
    val prov: String,
    val spd: Double,
    val bear: Double
)

/**
 * One-line JSON, SPEC.md section 9:
 * `{"lat":39.9,"lon":116.4,"acc":12.0,"ts":1760490000,"prov":"gps","spd":0.0,"bear":0.0}`
 *
 * Coordinates keep 6 decimals (trailing zeros stripped); accuracy, speed and
 * bearing keep exactly one.
 */
internal fun locationJson(fix: LocationFix): String {
    return buildString {
        append("{\"lat\":").append(decimal(fix.lat, 6))
        append(",\"lon\":").append(decimal(fix.lon, 6))
        append(",\"acc\":").append(decimal(fix.acc, 1))
        append(",\"ts\":").append(fix.ts)
        append(",\"prov\":\"").append(escape(fix.prov)).append('"')
        append(",\"spd\":").append(decimal(fix.spd, 1))
        append(",\"bear\":").append(decimal(fix.bear, 1))
        append('}')
    }
}

private fun decimal(value: Double, digits: Int): String {
    val text = String.format(Locale.US, "%." + digits + "f", value)
    return if (digits > 1) stripTrailingZeros(text) else text
}

private fun stripTrailingZeros(text: String): String {
    if (!text.contains('.')) return text
    var end = text.length
    while (end > 1 && text[end - 1] == '0') end--
    if (end > 0 && text[end - 1] == '.') end--
    return text.substring(0, end)
}

private fun escape(value: String): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        sb.append(if (c == '"' || c == '\\') '\'' else c)
        i++
    }
    return sb.toString()
}

/**
 * One-shot fixes plus the periodic reporter behind `locloop` / `locstop`
 * (SPEC.md section 9).
 */
class LocationHelper(private val context: Context) {

    /** Interval of the running reporter in seconds, 0 when it is off. */
    @Volatile
    var loopInterval: Int = 0
        private set

    private val providers = arrayOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    private var loopThread: HandlerThread? = null
    private var loopHandler: Handler? = null

    fun hasPermission(): Boolean {
        return try {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * One fix, preferring GPS, then network, then passive. Falls back to
     * `getLastKnownLocation`.
     *
     * @return null when there is no permission, no provider or no fix.
     */
    fun oneShot(timeoutMs: Long = DEFAULT_TIMEOUT_MS): LocationFix? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val enabled = ArrayList<String>(providers.size)
        for (provider in providers) {
            if (isEnabled(manager, provider)) enabled.add(provider)
        }

        val best = AtomicReference<Location?>(null)
        if (!enabled.isEmpty()) {
            val latch = CountDownLatch(enabled.size)
            val executor = mainExecutor()
            for (provider in enabled) {
                val consumer = object : Consumer<Location?> {
                    override fun accept(location: Location?) {
                        if (location != null && best.get() == null) best.set(location)
                        latch.countDown()
                    }
                }
                try {
                    manager.getCurrentLocation(provider, null, executor, consumer)
                } catch (t: Throwable) {
                    latch.countDown()
                }
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        var location = best.get()
        if (location == null) {
            for (provider in providers) {
                try {
                    val last = manager.getLastKnownLocation(provider)
                    if (last != null) {
                        location = last
                        break
                    }
                } catch (t: Throwable) {
                    // Provider without a cached fix: try the next one.
                }
            }
        }

        val fix = location ?: return null
        return LocationFix(
            lat = fix.latitude,
            lon = fix.longitude,
            acc = fix.accuracy.toDouble(),
            ts = fix.time / 1000L,
            prov = fix.provider ?: "unknown",
            spd = fix.speed.toDouble(),
            bear = fix.bearing.toDouble()
        )
    }

    /**
     * Report one fix every [intervalSeconds] seconds (clamped to 30..3600).
     * The first fix is taken immediately.
     */
    fun startLoop(intervalSeconds: Int, sink: (LocationFix) -> Unit) {
        stopLoop()
        val seconds = intervalSeconds.coerceIn(MIN_LOOP_SECONDS, MAX_LOOP_SECONDS)
        loopInterval = seconds
        val period = seconds * 1000L

        val thread = HandlerThread("phoneagent-locloop")
        thread.start()
        val handler = Handler(thread.looper)
        loopThread = thread
        loopHandler = handler

        val repeater = object : Runnable {
            override fun run() {
                val fix = oneShot(DEFAULT_TIMEOUT_MS)
                if (fix != null) {
                    try {
                        sink(fix)
                    } catch (t: Throwable) {
                        // A failed report must not kill the repeater.
                    }
                }
                if (loopInterval > 0) handler.postDelayed(this, period)
            }
        }
        handler.post(repeater)
    }

    fun stopLoop() {
        loopInterval = 0
        val handler = loopHandler
        loopHandler = null
        if (handler != null) {
            try {
                handler.removeCallbacksAndMessages(null)
            } catch (t: Throwable) {
                // ignore
            }
        }
        val thread = loopThread
        loopThread = null
        if (thread != null) {
            try {
                thread.quitSafely()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun isEnabled(manager: LocationManager, provider: String): Boolean {
        return try {
            manager.isProviderEnabled(provider)
        } catch (t: Throwable) {
            false
        }
    }

    private fun mainExecutor(): Executor {
        val handler = Handler(Looper.getMainLooper())
        return object : Executor {
            override fun execute(runnable: Runnable) {
                handler.post(runnable)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MIN_LOOP_SECONDS = 30
        const val MAX_LOOP_SECONDS = 3600
    }
}
