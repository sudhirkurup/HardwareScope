package com.hardwarescope.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.Display
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Kind { AUTO, MANUAL, INTERACTIVE }

class TestDef(
    val id: String,
    val name: String,
    val desc: String,
    val kind: Kind = Kind.AUTO,
    val perms: List<String> = emptyList(),
    /** If set, the user is asked this question after the test to record PASS / FAIL. */
    val confirm: String? = null,
    val run: ((progress: (String) -> Unit) -> String)? = null
)

/** All hardware tests. Each `run` executes on a background thread and returns a one-line result. */
@SuppressLint("MissingPermission")
class TestSuite(private val act: Activity, private val probe: Probe) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var sink = 0.0

    @Volatile
    private var singleCore = 0.0

    val tests: List<TestDef> = listOf(
        TestDef("cpu1", "CPU · single-core", "Mixed integer and floating-point work on one core for 3 seconds.") { p ->
            p("Running…")
            val r = cpuWork(3000) / 3e6
            singleCore = r
            f("%.1f M ops/s", r)
        },
        TestDef("cpuN", "CPU · all cores", "Same workload on every core at once (shows multi-core scaling and throttling).") { p ->
            val n = Runtime.getRuntime().availableProcessors()
            p("Running on $n threads…")
            val total = AtomicLong()
            val latch = CountDownLatch(n)
            repeat(n) {
                Thread {
                    total.addAndGet(cpuWork(3000)); latch.countDown()
                }.start()
            }
            latch.await(20, TimeUnit.SECONDS)
            val r = total.get() / 3e6
            val scale = if (singleCore > 0) f(" · %.1f× single-core", r / singleCore) else ""
            f("%.1f M ops/s on %d threads%s", r, n, scale)
        },
        TestDef("mem", "Memory · speed", "Copy bandwidth on a large block, plus random-access latency.") { p ->
            p("Measuring bandwidth…")
            val bytes = min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)
            val n = (bytes / 8).toInt()
            val a = LongArray(n) { it.toLong() }
            val b = LongArray(n)
            var copied = 0L
            val t0 = System.nanoTime()
            while (System.nanoTime() - t0 < 2_000_000_000L) {
                System.arraycopy(a, 0, b, 0, n); copied += n * 8L
            }
            val bw = copied / ((System.nanoTime() - t0) / 1e9) / 1e9
            sink += b[n / 2].toDouble()
            p("Measuring latency…")
            // Pointer chase through a random single cycle (Sattolo) defeats prefetching.
            val m = min(4 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 16).toInt())
            val next = IntArray(m) { it }
            for (i in m - 1 downTo 1) {
                val j = Random.nextInt(i); val t = next[i]; next[i] = next[j]; next[j] = t
            }
            var idx = 0
            val steps = 3_000_000
            val t1 = System.nanoTime()
            for (s in 0 until steps) idx = next[idx]
            val ns = (System.nanoTime() - t1).toDouble() / steps
            sink += idx
            f("Copy %.1f GB/s · random access %.0f ns", bw, ns)
        },
        TestDef("storage", "Storage · speed", "Writes and reads a temporary file (up to 128 MB), then random 4 KB writes. File is deleted afterwards.") { p ->
            val file = File(act.filesDir, "hs_bench.tmp")
            try {
                val free = StatFs(act.filesDir.path).availableBytes
                val size = min(128L shl 20, free / 4).coerceAtLeast(8L shl 20)
                val buf = ByteArray(1 shl 20) { it.toByte() }
                p("Sequential write…")
                var t0 = System.nanoTime()
                FileOutputStream(file).use { o ->
                    var w = 0L
                    while (w < size) { o.write(buf); w += buf.size }
                    o.fd.sync()
                }
                val write = size / 1e6 / ((System.nanoTime() - t0) / 1e9)
                p("Sequential read…")
                t0 = System.nanoTime()
                FileInputStream(file).use { i -> while (i.read(buf) > 0) { } }
                val read = size / 1e6 / ((System.nanoTime() - t0) / 1e9)
                p("Random 4 KB writes…")
                var ops = 0
                val small = ByteArray(4096)
                RandomAccessFile(file, "rwd").use { r ->
                    val blocks = size / 4096
                    t0 = System.nanoTime()
                    while (System.nanoTime() - t0 < 2_000_000_000L) {
                        r.seek(Random.nextLong(blocks) * 4096); r.write(small); ops++
                    }
                }
                val iops = ops / ((System.nanoTime() - t0) / 1e9)
                f("Write %.0f MB/s · Read %.0f MB/s* · 4K random write %.0f IOPS  (*may be cached)", write, read, iops)
            } finally {
                file.delete()
            }
        },
        TestDef("gpu", "GPU · shader benchmark", "Renders a math-heavy shader off-screen at 1024×1024 for 3 seconds.") { p ->
            p("Rendering…")
            Gpu.benchmark()
        },
        TestDef("display", "Display · refresh rate", "Counts real frames delivered by the screen for ~2 seconds at the highest refresh mode.") { p ->
            p("Counting frames…")
            measureRefresh()
        },
        TestDef("sensors", "Sensors · sampling rate", "Reads motion & environment sensors at maximum speed for 2.5 seconds.") { p ->
            p("Sampling…")
            sensorRates()
        },
        TestDef("network", "Network · latency & speed", "TCP latency to Cloudflare and a download of up to 50 MB (uses mobile data if not on Wi-Fi).") { p ->
            networkTest(p)
        },
        TestDef("battery", "Battery · drain rate", "Samples battery current for 10 seconds and estimates time remaining.") { p ->
            batteryTest(p)
        },
        TestDef("vibrate", "Vibration motor", "Plays a 3-pulse vibration pattern.", Kind.MANUAL, confirm = "Did you feel three vibration pulses?") { _ ->
            vibrate()
        },
        TestDef("flash", "Flashlight", "Turns the camera flash on for 1.5 seconds.", Kind.MANUAL, confirm = "Did the flashlight turn on?") { _ ->
            flash()
        },
        TestDef("speaker", "Speakers (stereo)", "Plays a tone from the LEFT speaker, then the RIGHT. Turn media volume up.", Kind.MANUAL,
            confirm = "Did you hear the tone, first from the left and then from the right?") { p ->
            p("Playing…")
            tone()
        },
        TestDef("mic", "Microphone", "Records 3 seconds — speak or clap while it runs. Nothing is saved.", Kind.MANUAL,
            perms = listOf(Manifest.permission.RECORD_AUDIO)) { p ->
            p("Listening… speak or clap now")
            mic()
        },
        TestDef("gps", "GPS / GNSS fix", "Searches for satellites for up to 45 s. Works best outdoors or near a window.", Kind.MANUAL,
            perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { p ->
            gps(p)
        },
        TestDef("touch", "Touchscreen · multi-touch", "Put as many fingers on the screen as you can. Press Back when done.", Kind.INTERACTIVE),
        TestDef("pixels", "Display · dead pixels", "Fills the screen with solid colours. Tap to go to the next colour.", Kind.INTERACTIVE)
    )

    private fun cpuWork(ms: Long): Long {
        var ops = 0L
        var x = 1.0
        var acc = 0L
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            for (i in 1..20_000) {
                x = x * 1.000001 + 0.5 / i
                acc += (i xor (acc.toInt() shl 1)).toLong()
            }
            ops += 20_000
        }
        sink += x + acc
        return ops
    }

    private fun measureRefresh(): String {
        val dm = act.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        // Ask for the fastest mode during the measurement (Android 6+).
        if (sdk(23)) main.post {
            val best = d.supportedModes.filter { it.physicalWidth == d.mode.physicalWidth }.maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = act.window.attributes; lp.preferredDisplayModeId = best.modeId; act.window.attributes = lp
            }
        }
        Thread.sleep(400)
        val times = LongArray(150)
        var i = 0
        val latch = CountDownLatch(1)
        main.post {
            val ch = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    times[i++] = frameTimeNanos
                    act.window.decorView.invalidate()
                    if (i < times.size) ch.postFrameCallback(this) else latch.countDown()
                }
            }
            ch.postFrameCallback(cb)
        }
        latch.await(10, TimeUnit.SECONDS)
        if (sdk(23)) main.post { val lp = act.window.attributes; lp.preferredDisplayModeId = 0; act.window.attributes = lp }
        val n = i
        if (n < 10) return "Could not measure"
        val diffs = (1 until n).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
        val median = diffs[diffs.size / 2]
        val measured = 1e9 / median
        val janky = diffs.count { it > median * 1.5 }
        return f("%.1f Hz measured (panel reports %.0f Hz) · %d slow frames of %d", measured, d.refreshRate, janky, diffs.size)
    }

    private fun sensorRates(): String {
        val sm = act.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return "No sensor service"
        val types = listOf(
            Sensor.TYPE_ACCELEROMETER to "Accel", Sensor.TYPE_GYROSCOPE to "Gyro", Sensor.TYPE_MAGNETIC_FIELD to "Mag",
            Sensor.TYPE_PRESSURE to "Baro", Sensor.TYPE_LIGHT to "Light", Sensor.TYPE_PROXIMITY to "Prox"
        )
        val counts = HashMap<Int, Int>()
        val ht = HandlerThread("rate").also { it.start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val t = event.sensor.type; counts[t] = (counts[t] ?: 0) + 1
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val present = types.filter { sm.getDefaultSensor(it.first) != null }
        present.forEach { sm.registerListener(l, sm.getDefaultSensor(it.first), SensorManager.SENSOR_DELAY_FASTEST, Handler(ht.looper)) }
        Thread.sleep(2500)
        sm.unregisterListener(l)
        ht.quitSafely()
        if (present.isEmpty()) return "No motion sensors"
        return present.joinToString(" · ") { (t, n) -> f("%s %.0f Hz", n, (counts[t] ?: 0) / 2.5) }
    }

    private fun networkTest(p: (String) -> Unit): String {
        p("Measuring latency…")
        val lat = ArrayList<Double>()
        repeat(5) {
            try {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress("1.1.1.1", 443), 3000)
                    lat += (System.nanoTime() - t0) / 1e6
                }
            } catch (_: Exception) {
            }
        }
        if (lat.isEmpty()) return "No internet connection"
        val dnsMs = try {
            val t0 = System.nanoTime(); InetAddress.getByName("speed.cloudflare.com"); (System.nanoTime() - t0) / 1e6
        } catch (e: Exception) { -1.0 }
        p("Downloading…")
        var bytes = 0L
        var secs = 0.0
        try {
            val c = URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            c.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                val t0 = System.nanoTime()
                while (true) {
                    val r = ins.read(buf); if (r < 0) break
                    bytes += r
                    secs = (System.nanoTime() - t0) / 1e9
                    if (secs > 8) break
                    if (bytes % (2 shl 20) < r) p(f("Downloading… %.0f Mbps", bytes * 8 / secs / 1e6))
                }
            }
            c.disconnect()
        } catch (e: Exception) {
            if (bytes == 0L) return f("Latency %.0f ms · download failed (%s)", lat.sorted()[lat.size / 2], e.javaClass.simpleName)
        }
        val mbps = if (secs > 0) bytes * 8 / secs / 1e6 else 0.0
        return f("Latency %.0f ms · DNS %.0f ms · Download %.1f Mbps", lat.sorted()[lat.size / 2], dnsMs, mbps)
    }

    private fun batteryTest(p: (String) -> Unit): String {
        val samples = ArrayList<Float>()
        repeat(20) {
            probe.batteryCurrentMa()?.let { samples += it }
            p("Sampling ${it + 1}/20…")
            Thread.sleep(500)
        }
        if (samples.isEmpty()) return "This phone does not report battery current"
        val avg = samples.average()
        val bm = act.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val cc = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val est = if (cc > 0 && abs(avg) > 1) f(" · ≈ %.1f h left at this rate", cc / 1000.0 / abs(avg)) else ""
        return f("Average %.0f mA (min %.0f, max %.0f)%s", avg, samples.minOrNull() ?: 0f, samples.maxOrNull() ?: 0f, est)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(): String {
        val v: Vibrator? = if (sdk(31)) (act.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else act.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (v == null || !v.hasVibrator()) return "No vibration motor"
        val pattern = longArrayOf(0, 250, 200, 250, 200, 250)
        if (sdk(26)) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
        Thread.sleep(1300)
        return "Pattern played" + if (sdk(26) && v.hasAmplitudeControl()) " (strength control supported)" else ""
    }

    private fun flash(): String {
        if (!sdk(23)) return "Requires Android 6.0+"
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "No flashlight found"
        cm.setTorchMode(id, true)
        Thread.sleep(1500)
        cm.setTorchMode(id, false)
        return "Flash on camera $id toggled"
    }

    @Suppress("DEPRECATION")
    private fun tone(): String {
        val rate = 44100
        val secs = 1.0
        val n = (rate * secs).toInt()
        val data = ShortArray(n * 4) // left segment then right segment, stereo interleaved
        for (i in 0 until n) {
            val s = (sin(2 * PI * 880 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
            data[i * 2] = s; data[i * 2 + 1] = 0           // left only
            data[(n + i) * 2] = 0; data[(n + i) * 2 + 1] = s // right only
        }
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            data.size * 2, AudioTrack.MODE_STATIC
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release(); return "Audio output unavailable"
        }
        track.write(data, 0, data.size)
        track.play()
        Thread.sleep((secs * 2 * 1000 + 300).toLong())
        track.stop(); track.release()
        return "880 Hz tone played left → right"
    }

    private fun mic(): String {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "Microphone unavailable"
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return "Microphone busy or blocked"
        }
        val buf = ShortArray(4096)
        var peak = 0
        var sumSq = 0.0
        var count = 0L
        rec.startRecording()
        val end = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < end) {
            val r = rec.read(buf, 0, buf.size)
            for (i in 0 until max(r, 0)) {
                val v = abs(buf[i].toInt()); if (v > peak) peak = v
                sumSq += v.toDouble() * v; count++
            }
        }
        rec.stop(); rec.release()
        if (count == 0L) return "No audio captured"
        fun db(x: Double) = if (x <= 0) -96.0 else 20 * log10(x / 32768.0)
        val rms = sqrt(sumSq / count)
        val verdict = if (peak < 200) " · very quiet — mic may be blocked" else ""
        return f("Peak %.1f dBFS · average %.1f dBFS%s", db(peak.toDouble()), db(rms), verdict)
    }

    @Suppress("DEPRECATION")
    private fun gps(p: (String) -> Unit): String {
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "GPS is switched off in Settings"
        val ht = HandlerThread("gps").also { it.start() }
        val h = Handler(ht.looper)
        val start = SystemClock.elapsedRealtime()
        var ttff = -1L
        var acc = 0f
        var sats = 0
        var used = 0
        val constellations = java.util.Collections.synchronizedSortedSet(sortedSetOf<String>())
        var dual = false
        val fix = CountDownLatch(1)

        val ll = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (ttff < 0) {
                    ttff = SystemClock.elapsedRealtime() - start; acc = location.accuracy; fix.countDown()
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        var gcb: Any? = null
        if (sdk(24)) {
            val cb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    sats = status.satelliteCount
                    var u = 0
                    for (i in 0 until sats) {
                        if (status.usedInFix(i)) u++
                        constellations.add(
                            when (status.getConstellationType(i)) {
                                GnssStatus.CONSTELLATION_GPS -> "GPS"; GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                                GnssStatus.CONSTELLATION_GALILEO -> "Galileo"; GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                                GnssStatus.CONSTELLATION_QZSS -> "QZSS"; GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                                7 -> "NavIC"; else -> "Other"
                            }
                        )
                        if (sdk(26) && status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) < 1.3e9f) dual = true
                    }
                    used = u
                }
            }
            lm.registerGnssStatusCallback(cb, h)
            gcb = cb
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, ht.looper)
        val deadline = start + 45_000
        while (SystemClock.elapsedRealtime() < deadline && fix.count > 0) {
            p(f("Searching… %ds · %d satellites in view, %d used", (SystemClock.elapsedRealtime() - start) / 1000, sats, used))
            fix.await(1, TimeUnit.SECONDS)
        }
        if (ttff >= 0) Thread.sleep(2000) // let satellite status settle after the fix
        lm.removeUpdates(ll)
        if (sdk(24)) (gcb as? GnssStatus.Callback)?.let { lm.unregisterGnssStatusCallback(it) }
        ht.quitSafely()
        val consts = if (constellations.isEmpty()) "" else " · " + constellations.joinToString(", ")
        val l5 = if (sdk(26) && sats > 0) " · dual-frequency (L5/E5): " + (if (dual) "yes" else "not seen") else ""
        val res = if (ttff >= 0) f("Fix in %.1f s (±%.0f m) · %d satellites, %d used%s%s", ttff / 1000.0, acc, sats, used, consts, l5)
        else f("No fix in 45 s · %d satellites in view%s — try outdoors", sats, consts)
        probe.lastGnss = res
        return res
    }
}
