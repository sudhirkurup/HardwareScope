package com.hardwarescope.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import java.io.File
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reads every piece of hardware information the app shows.
 * Every call that depends on a newer Android version is guarded with sdk(x), and every
 * value is wrapped so that a missing API, permission or vendor quirk shows "N/A" instead of crashing.
 */
@SuppressLint("MissingPermission", "HardwareIds")
class Probe(private val ctx: Context) {
    private val app = ctx.applicationContext
    private val pm = ctx.packageManager
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    var lastGnss: String? = null

    private fun feature(name: String) = pm.hasSystemFeature(name)

    fun static(c: Category): List<Row> = try {
        when (c) {
            Category.SYSTEM -> system()
            Category.CPU -> cpuStatic()
            Category.GPU -> gpuStatic()
            Category.MEMORY -> memStatic()
            Category.STORAGE -> storageStatic()
            Category.BATTERY -> batteryStatic()
            Category.THERMAL -> thermalStatic()
            Category.DISPLAY -> displayStatic()
            Category.SENSORS -> sensorsStatic()
            Category.CAMERA -> cameras()
            Category.AUDIO -> audioStatic()
            Category.NETWORK -> networkStatic()
            Category.CONNECTIVITY -> connStatic()
            Category.LOCATION -> locationStatic()
            Category.SECURITY -> security()
            Category.OTHER -> other()
            Category.FEATURES -> features()
        }
    } catch (t: Throwable) {
        listOf(Row("Error", t.toString()))
    }

    fun live(c: Category): Live = try {
        when (c) {
            Category.SYSTEM -> systemLive()
            Category.CPU -> cpuLive()
            Category.GPU -> gpuLive()
            Category.MEMORY -> memLive()
            Category.STORAGE -> storageLive()
            Category.BATTERY -> batteryLive()
            Category.THERMAL -> thermalLive()
            Category.DISPLAY -> displayLive()
            Category.SENSORS -> sensorsLive()
            Category.AUDIO -> audioLive()
            Category.NETWORK -> networkLive()
            Category.CONNECTIVITY -> connLive()
            Category.LOCATION -> locationLive()
            else -> Live(emptyList())
        }
    } catch (t: Throwable) {
        Live(listOf(Row("Live data", "Unavailable: ${t.javaClass.simpleName}")))
    }

    // ------------------------------------------------------------------ SYSTEM

    private fun system() = rows {
        add("Manufacturer") { Build.MANUFACTURER.cap() }
        add("Brand") { Build.BRAND }
        add("Model") { Build.MODEL }
        opt("Device name") { if (sdk(25)) Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) else null }
        add("Device / product") { "${Build.DEVICE} / ${Build.PRODUCT}" }
        add("Board") { Build.BOARD }
        add("Hardware") { Build.HARDWARE }
        add("System-on-Chip") { if (sdk(31)) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else cpuInfo()["Hardware"] }
        add("Android version") { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
        opt("Security patch") { if (sdk(23)) Build.VERSION.SECURITY_PATCH else null }
        add("Build") { Build.DISPLAY }
        add("Build fingerprint") { Build.FINGERPRINT }
        add("Bootloader") { Build.BOOTLOADER }
        add("Baseband / radio") { Build.getRadioVersion() }
        add("Kernel") { System.getProperty("os.version") }
        add("Supported ABIs") { Build.SUPPORTED_ABIS.joinToString(", ") }
        add("Runtime") { "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}" }
        add("Language / time zone") { "${Locale.getDefault()} · ${java.util.TimeZone.getDefault().id}" }
    }

    private fun systemLive(): Live {
        val up = SystemClock.elapsedRealtime()
        val awake = SystemClock.uptimeMillis()
        return Live(rows {
            add("Time since boot") { fmtDuration(up) }
            add("Time awake (CPU running)") { fmtDuration(awake) }
            add("Time in deep sleep") { f("%s (%.0f%%)", fmtDuration(up - awake), (up - awake) * 100.0 / max(up, 1)) }
            add("Battery saver") { power.isPowerSaveMode }
            opt("Device idle (Doze)") { if (sdk(23)) power.isDeviceIdleMode else null }
        })
    }

    // ------------------------------------------------------------------ CPU

    private var cpuInfoCache: Map<String, String>? = null
    private val corePart = HashMap<Int, String>()

    private fun cpuInfo(): Map<String, String> {
        cpuInfoCache?.let { return it }
        val map = LinkedHashMap<String, String>()
        var proc = -1
        readText("/proc/cpuinfo")?.lineSequence()?.forEach { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@forEach
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (k == "processor") proc = v.toIntOrNull() ?: proc
            if (k == "CPU part" && proc >= 0) corePart[proc] = v
            if (!map.containsKey(k)) map[k] = v
        }
        cpuInfoCache = map
        return map
    }

    private val armCores = mapOf(
        "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd07" to "Cortex-A57",
        "0xd08" to "Cortex-A72", "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76",
        "0xd0c" to "Neoverse-N1", "0xd0d" to "Cortex-A77", "0xd41" to "Cortex-A78", "0xd44" to "Cortex-X1",
        "0xd46" to "Cortex-A510", "0xd47" to "Cortex-A710", "0xd48" to "Cortex-X2", "0xd4d" to "Cortex-A715",
        "0xd4e" to "Cortex-X3", "0xd80" to "Cortex-A520", "0xd81" to "Cortex-A720", "0xd82" to "Cortex-X4",
        "0xd85" to "Cortex-X925", "0xd87" to "Cortex-A725", "0xd4b" to "Cortex-A78C", "0x800" to "Kryo Gold",
        "0x801" to "Kryo Silver", "0x802" to "Kryo Gold", "0x803" to "Kryo Silver", "0x804" to "Kryo Gold", "0x805" to "Kryo Silver"
    )

    fun coreCount(): Int {
        val n = File("/sys/devices/system/cpu").list()?.count { it.matches(Regex("cpu[0-9]+")) } ?: 0
        return if (n > 0) n else Runtime.getRuntime().availableProcessors()
    }

    private fun cpuStatic(): List<Row> {
        val info = cpuInfo()
        val n = coreCount()
        return rows {
            add("Cores") { "$n (${Runtime.getRuntime().availableProcessors()} available to apps)" }
            add("Processor") { info["Hardware"] ?: info["model name"] ?: info["Processor"] ?: Build.HARDWARE }
            // Group cores into clusters by micro-architecture and max clock.
            val clusters = LinkedHashMap<String, LongArray>() // key -> [count, maxKhz]
            for (i in 0 until n) {
                val maxF = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                val part = corePart[i]?.lowercase(Locale.US)
                val name = armCores[part] ?: (part?.let { "Core $it" } ?: "Core")
                val key = if (maxF != null) "$name @ ${fmtKhz(maxF)}" else name
                val e = clusters.getOrPut(key) { longArrayOf(0, maxF ?: 0) }
                e[0]++
            }
            clusters.entries.sortedByDescending { it.value[1] }
                .forEachIndexed { idx, e -> add("Cluster ${idx + 1}") { "${e.value[0]} × ${e.key}" } }
            opt("Min clock") { readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")?.let { fmtKhz(it) } }
            opt("Governor") { readText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") }
            add("Architecture") { Build.SUPPORTED_ABIS.firstOrNull() }
            opt("Instruction features") { (info["Features"] ?: info["flags"])?.take(160) }
        }
    }

    private var prevStat: LongArray? = null

    private fun procStatUsage(): Float? {
        val line = readText("/proc/stat")?.lineSequence()?.firstOrNull() ?: return null
        val p = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (p.size < 4) return null
        val idle = p[3] + (p.getOrNull(4) ?: 0)
        val total = p.sum()
        val prev = prevStat
        prevStat = longArrayOf(idle, total)
        if (prev == null) return null
        val dt = total - prev[1]
        if (dt <= 0) return null
        return (100f * (dt - (idle - prev[0])) / dt).coerceIn(0f, 100f)
    }

    private fun cpuLive(): Live {
        val n = coreCount()
        var sumCur = 0L
        var sumMax = 0L
        val list = ArrayList<Row>()
        for (i in 0 until n) {
            val online = readText("/sys/devices/system/cpu/cpu$i/online") != "0"
            val cur = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val mx = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val v = when {
                !online -> "offline (parked)"
                cur == null -> "not readable"
                mx != null && mx > 0 -> f("%s  ·  %d%% of max", fmtKhz(cur), cur * 100 / mx)
                else -> fmtKhz(cur)
            }
            if (cur != null && mx != null && online) {
                sumCur += cur; sumMax += mx
            } else if (mx != null) sumMax += mx
            list.add(Row("Core $i", v))
        }
        val usage = procStatUsage()
        val load = if (sumMax > 0) sumCur * 100f / sumMax else null
        if (usage != null) list.add(0, Row("CPU usage", f("%.0f%%", usage)))
        if (load != null) list.add(0, Row("Clock load (all cores)", f("%.0f%% of max frequency", load)))
        val spark = usage ?: load
        return Live(list, spark, if (usage != null) "CPU usage" else "Clock load", "%", 100f)
    }

    // ------------------------------------------------------------------ GPU

    private fun gpuStatic(): List<Row> {
        val g = Gpu.info()
        return rows {
            add("Renderer (GPU)") { g?.renderer }
            add("Vendor") { g?.vendor }
            add("OpenGL ES") { g?.version }
            add("GLSL") { g?.glsl }
            add("Max texture size") { g?.let { "${it.maxTexture} px" } }
            add("GL extensions") { g?.extensions }
            add("OpenGL ES declared") {
                val v = am.deviceConfigurationInfo.reqGlEsVersion
                "${v shr 16}.${v and 0xffff}"
            }
            add("Vulkan") {
                if (!sdk(24)) "Requires Android 7+"
                else {
                    val feat = pm.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.version" }
                    if (feat == null) "Not supported" else "${feat.version shr 22}.${(feat.version shr 12) and 0x3ff}"
                }
            }
            opt("GPU max clock") { gpuPath(listOf("/sys/class/kgsl/kgsl-3d0/max_gpuclk"))?.let { f("%d MHz", it / 1_000_000) } }
        }
    }

    private fun gpuPath(paths: List<String>): Long? {
        for (p in paths) readText(p)?.let { t -> Regex("-?\\d+").find(t)?.value?.toLongOrNull()?.let { return it } }
        return null
    }

    private fun gpuLive(): Live {
        val busy = gpuPath(
            listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/kernel/gpu/gpu_busy",
                "/sys/devices/platform/gpusysfs/gpu_busy",
                "/sys/class/misc/mali0/device/utilization"
            )
        )
        val clk = gpuPath(listOf("/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/kernel/gpu/gpu_clock", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"))
        val list = rows {
            if (busy != null) add("GPU load") { "$busy%" }
            if (clk != null) add("GPU clock") { if (clk > 100_000) "${clk / 1_000_000} MHz" else "$clk MHz" }
            if (busy == null && clk == null) add("GPU load / clock") { "Not exposed by this device (blocked by Android security)" }
        }
        return Live(list, busy?.toFloat(), "GPU load", "%", 100f)
    }

    // ------------------------------------------------------------------ MEMORY

    private fun memInfo(): Map<String, Long> {
        val m = HashMap<String, Long>()
        readText("/proc/meminfo")?.lineSequence()?.forEach { l ->
            val k = l.substringBefore(':')
            val v = l.substringAfter(':').trim().split(" ").firstOrNull()?.toLongOrNull()
            if (v != null) m[k] = v * 1024
        }
        return m
    }

    private fun marketedGb(bytes: Long): Int {
        val gb = bytes / 1_073_741_824.0
        return listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32, 64).firstOrNull { it >= gb } ?: gb.toInt()
    }

    private fun memStatic(): List<Row> {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mem = memInfo()
        return rows {
            add("Total RAM") { f("%s (sold as %d GB)", fmtBytes(mi.totalMem), marketedGb(mi.totalMem)) }
            opt("Swap / zRAM") { mem["SwapTotal"]?.takeIf { it > 0 }?.let { fmtBytes(it) } }
            add("Low-memory threshold") { fmtBytes(mi.threshold) }
            add("Low-RAM device") { am.isLowRamDevice }
            add("App memory class") { "${am.memoryClass} MB (large heap ${am.largeMemoryClass} MB)" }
            add("Max app heap") { fmtBytes(Runtime.getRuntime().maxMemory()) }
        }
    }

    private fun memLive(): Live {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = mi.totalMem - mi.availMem
        val pct = used * 100f / max(mi.totalMem, 1)
        val mem = memInfo()
        val rt = Runtime.getRuntime()
        return Live(rows {
            add("RAM used") { f("%s (%.0f%%)", fmtBytes(used), pct) }
            add("RAM available") { fmtBytes(mi.availMem) }
            opt("Cached") { mem["Cached"]?.let { fmtBytes(it) } }
            opt("Swap used") {
                val t = mem["SwapTotal"] ?: 0; val fr = mem["SwapFree"] ?: 0
                if (t > 0) fmtBytes(t - fr) else null
            }
            add("System low on memory") { mi.lowMemory }
            add("This app's heap") { fmtBytes(rt.totalMemory() - rt.freeMemory()) }
        }, pct, "RAM used", "%", 100f)
    }

    // ------------------------------------------------------------------ STORAGE

    private fun storageStatic(): List<Row> = rows {
        val st = StatFs(Environment.getDataDirectory().path)
        add("Internal storage") { fmtBytes(st.totalBytes) }
        opt("File system") {
            readText("/proc/mounts")?.lineSequence()?.map { it.split(" ") }
                ?.firstOrNull { it.size > 2 && it[1] == "/data" }?.get(2)
        }
        opt("Storage type") {
            val blocks = File("/sys/block").list()?.toList() ?: emptyList()
            when {
                blocks.any { it.startsWith("sda") } -> "UFS (likely)"
                blocks.any { it.startsWith("mmcblk0") } -> "eMMC (likely)"
                blocks.any { it.startsWith("nvme") } -> "NVMe"
                else -> null
            }
        }
        val dirs = ctx.getExternalFilesDirs(null)
        var sd = 0
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            sd++
            add("SD card / USB $sd") {
                val s = StatFs(d.path)
                "${fmtBytes(s.totalBytes)} total · ${fmtBytes(s.availableBytes)} free"
            }
        }
        if (sd == 0) add("SD card / USB storage") { "None inserted" }
        if (sdk(24)) {
            val stm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            add("Storage volumes") {
                stm.storageVolumes.joinToString(" · ") { v ->
                    v.getDescription(ctx) + (if (v.isRemovable) " (removable)" else "")
                }
            }
        }
    }

    private fun storageLive(): Live {
        val st = StatFs(Environment.getDataDirectory().path)
        val used = st.totalBytes - st.availableBytes
        val pct = used * 100f / max(st.totalBytes, 1)
        return Live(rows {
            add("Used") { f("%s (%.0f%%)", fmtBytes(used), pct) }
            add("Free") { fmtBytes(st.availableBytes) }
        }, pct, "Storage used", "%", 100f)
    }

    // ------------------------------------------------------------------ BATTERY

    fun batteryIntent(): Intent? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        return if (sdk(33)) app.registerReceiver(null, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(null, filter)
    }

    /** Instantaneous battery current in mA (negative = discharging on most devices), or null. */
    fun batteryCurrentMa(): Float? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        return if (abs(raw) > 10_000) raw / 1000f else raw.toFloat()
    }

    private fun healthName(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> "Unknown"
    }

    private fun batteryStatic(): List<Row> {
        val i = batteryIntent()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return rows {
            add("Battery present") { i?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) }
            add("Technology") { i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) }
            add("Health") { i?.let { healthName(it.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) } }
            opt("Design capacity") {
                val cls = Class.forName("com.android.internal.os.PowerProfile")
                val obj = cls.getConstructor(Context::class.java).newInstance(ctx)
                val cap = cls.getMethod("getBatteryCapacity").invoke(obj) as Double
                if (cap > 100) f("%.0f mAh", cap) else null
            }
            opt("Estimated full capacity") {
                val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (cc > 0 && lvl in 5..100) f("%.0f mAh (from charge counter)", cc / 1000.0 / lvl * 100) else null
            }
            opt("Charge cycles") {
                if (sdk(34)) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
            }
        }
    }

    private fun batteryLive(): Live {
        val i = batteryIntent()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / max(it.getIntExtra(BatteryManager.EXTRA_SCALE, 100), 1) }
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val ma = batteryCurrentMa()
        return Live(rows {
            add("Level") { level?.let { "$it%" } }
            add("Status") {
                when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                    else -> "Unknown"
                }
            }
            add("Power source") {
                when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    0 -> "Battery"
                    else -> "Other"
                }
            }
            add("Battery temperature") { i?.let { f("%.1f °C", it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0) } }
            add("Voltage") { if (mv > 0) f("%.3f V", if (mv > 100) mv / 1000.0 else mv.toDouble()) else null }
            add("Current") { ma?.let { f("%.0f mA", it) } ?: "Not reported" }
            opt("Power") {
                if (ma != null && mv > 0) f("%.2f W", abs(ma) / 1000.0 * (if (mv > 100) mv / 1000.0 else mv.toDouble())) else null
            }
            opt("Remaining charge") {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }?.let { f("%.0f mAh", it / 1000.0) }
            }
            opt("Time to full") {
                if (sdk(28)) bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { fmtDuration(it) } else null
            }
        }, ma?.let { abs(it) }, "Battery current", "mA")
    }

    // ------------------------------------------------------------------ THERMAL

    private fun zones(): List<Pair<String, Double>> {
        val dir = File("/sys/class/thermal")
        val list = dir.listFiles()?.filter { it.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 } ?: return emptyList()
        val out = ArrayList<Pair<String, Double>>()
        for (z in list) {
            val t = readLong(z.path + "/temp") ?: continue
            val c = if (abs(t) > 1000) t / 1000.0 else t.toDouble()
            if (c < -30 || c > 150 || c == 0.0) continue
            out.add((readText(z.path + "/type") ?: z.name) to c)
        }
        return out
    }

    private fun thermalStatic() = rows {
        add("Readable sensors (thermal zones)") { zones().size }
        add("Thermal status API") { if (sdk(29)) "Supported" else "Requires Android 10+" }
        add("Thermal headroom API") { if (sdk(30)) "Supported" else "Requires Android 11+" }
    }

    private fun thermalLive(): Live {
        val z = zones()
        val bt = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)
        val hot = z.filter { (n, _) -> n.contains("cpu", true) || n.contains("soc", true) || n.contains("tsens", true) || n.contains("big", true) }
            .maxOfOrNull { it.second }
        val list = rows {
            opt("Thermal status") {
                if (sdk(29)) when (power.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Normal"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
                    else -> "Unknown"
                } else null
            }
            opt("Thermal headroom") {
                if (sdk(30)) power.getThermalHeadroom(10).takeIf { !it.isNaN() }?.let { f("%.2f (1.00 = throttling)", it) } else null
            }
            add("Battery") { bt?.let { f("%.1f °C", it) } }
            z.take(30).forEach { (n, c) -> add(n) { f("%.1f °C", c) } }
            if (z.isEmpty()) add("Chip temperatures") { "Hidden by this Android version / manufacturer" }
        }
        val spark = hot ?: bt
        return Live(list, spark?.toFloat(), if (hot != null) "Hottest CPU zone" else "Battery temp", "°C")
    }

    // ------------------------------------------------------------------ DISPLAY

    private fun display(): Display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    @Suppress("DEPRECATION")
    private fun displayStatic(): List<Row> {
        val d = display()
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        val cfg = ctx.resources.configuration
        return rows {
            add("Resolution") { "${minOf(m.widthPixels, m.heightPixels)} × ${maxOf(m.widthPixels, m.heightPixels)} px" }
            add("Screen size") {
                val w = m.widthPixels / m.xdpi; val h = m.heightPixels / m.ydpi
                f("%.2f in diagonal", sqrt((w * w + h * h).toDouble()))
            }
            add("Density") {
                val b = when {
                    m.densityDpi <= 120 -> "ldpi"; m.densityDpi <= 160 -> "mdpi"; m.densityDpi <= 240 -> "hdpi"
                    m.densityDpi <= 320 -> "xhdpi"; m.densityDpi <= 480 -> "xxhdpi"; else -> "xxxhdpi"
                }
                "${m.densityDpi} dpi ($b)"
            }
            add("Physical pixel density") { f("%.0f × %.0f ppi", m.xdpi, m.ydpi) }
            add("Refresh rate (current)") { f("%.0f Hz", d.refreshRate) }
            opt("Refresh rates supported") {
                if (sdk(23)) d.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString(", ") { "$it Hz" } else null
            }
            opt("Display modes") {
                if (sdk(23)) d.supportedModes.map { "${it.physicalWidth}×${it.physicalHeight}@${it.refreshRate.toInt()}" }.distinct().joinToString(", ") else null
            }
            opt("HDR formats") {
                if (sdk(24)) {
                    val types = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                    if (types.isEmpty()) "None" else types.joinToString(", ") {
                        when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "Type $it" }
                    }
                } else null
            }
            opt("Peak brightness (HDR)") {
                if (sdk(24)) d.hdrCapabilities?.desiredMaxLuminance?.takeIf { it > 0 }?.let { f("%.0f nits", it) } else null
            }
            opt("Wide color gamut") { if (sdk(26)) cfg.isScreenWideColorGamut else null }
            opt("Round screen") { if (sdk(23)) cfg.isScreenRound else null }
            add("Display name") { d.name }
        }
    }

    private fun displayLive(): Live {
        val cr = ctx.contentResolver
        val b = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Throwable) { -1 }
        val pct = if (b >= 0) b * 100f / 255f else null
        val d = display()
        return Live(rows {
            add("Brightness setting") { pct?.let { f("%.0f%%", it) } }
            add("Auto brightness") {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
            add("Refresh rate now") { f("%.0f Hz", d.refreshRate) }
            add("Orientation") { if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait" }
            add("Dark theme") { (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
            add("Screen timeout") { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) / 1000 }
        }.map { if (it.name == "Screen timeout" && it.value != "N/A") Row(it.name, it.value + " s") else it }, pct, "Brightness", "%", 100f)
    }

    // ------------------------------------------------------------------ SENSORS

    val sensors: List<Sensor> by lazy { sm?.getSensorList(Sensor.TYPE_ALL) ?: emptyList() }

    /** Unique, human-friendly label per sensor (some phones list identical names twice). */
    val sensorLabels: Map<Sensor, String> by lazy {
        val seen = HashMap<String, Int>()
        val out = LinkedHashMap<Sensor, String>()
        for (s in sensors) {
            var base = s.name
            if (sdk(21) && s.isWakeUpSensor) base += " (wake-up)"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            out[s] = if (n > 1) "$base #$n" else base
        }
        out
    }

    fun sensorType(s: Sensor): String = if (sdk(20)) s.stringType.removePrefix("android.sensor.").replace('_', ' ') else "type ${s.type}"

    private fun sensorUnit(t: Int) = when (t) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> "m/s²"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD -> "µT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_STEP_COUNTER -> "steps"
        else -> ""
    }

    private fun sensorsStatic(): List<Row> = rows {
        add("Sensors found") { sensors.size }
        for (s in sensors) {
            add(sensorLabels[s] ?: s.name) {
                val rate = when {
                    s.minDelay > 0 -> f("up to %.0f Hz", 1_000_000.0 / s.minDelay)
                    s.minDelay == 0 -> "on change"
                    else -> "one-shot"
                }
                f("%s · %s · range %.4g · res %.3g · %.2f mA · %s", sensorType(s), s.vendor, s.maximumRange, s.resolution, s.power, rate)
            }
        }
    }

    private val latest = ConcurrentHashMap<Sensor, FloatArray>()
    private val counts = ConcurrentHashMap<Sensor, AtomicInteger>()
    private var sensorThread: HandlerThread? = null
    private var lastSensorRead = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            latest[event.sensor] = event.values.clone()
            counts.getOrPut(event.sensor) { AtomicInteger() }.incrementAndGet()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Synchronized
    fun startSensors() {
        val mgr = sm ?: return
        if (sensorThread != null) return
        val t = HandlerThread("sensors").also { it.start() }
        sensorThread = t
        val h = Handler(t.looper)
        lastSensorRead = SystemClock.elapsedRealtime()
        for (s in sensors) {
            if (sdk(21) && s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) continue
            try {
                mgr.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI, h)
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun stopSensors() {
        try { sm?.unregisterListener(listener) } catch (_: Throwable) {}
        sensorThread?.quitSafely()
        sensorThread = null
    }

    private fun sensorsLive(): Live {
        val now = SystemClock.elapsedRealtime()
        val secs = max(now - lastSensorRead, 1) / 1000f
        lastSensorRead = now
        val list = ArrayList<Row>()
        for (s in sensors) {
            val v = latest[s] ?: continue
            val hz = (counts[s]?.getAndSet(0) ?: 0) / secs
            val n = if (s.type == Sensor.TYPE_ROTATION_VECTOR || s.type == Sensor.TYPE_GAME_ROTATION_VECTOR) 4 else 3
            val vals = v.take(n).joinToString(", ") { f("%.2f", it) }
            list.add(Row(sensorLabels[s] ?: s.name, f("%s %s · %.0f Hz", vals, sensorUnit(s.type), hz).replace("  ", " ")))
        }
        if (list.isEmpty()) list.add(Row("Waiting for sensor data", "Move the phone"))
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { latest[it] }
        val mag = acc?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
        return Live(list, mag, "Acceleration", "m/s²")
    }

    fun sensorCount() = sensors.size

    // ------------------------------------------------------------------ CAMERA

    fun cameraIds(): Array<String> = try {
        (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList
    } catch (e: Throwable) {
        emptyArray()
    }

    private fun cameras(): List<Row> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cameraIds()
        return rows {
            add("Cameras found") { ids.size }
            for (id in ids) {
                val ch = try { cm.getCameraCharacteristics(id) } catch (e: Throwable) { continue }
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    else -> "External"
                }
                val px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                add("Camera $id · $facing") {
                    val parts = ArrayList<String>()
                    px?.let { parts += f("%.1f MP", it.width * it.height / 1e6) }
                    ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { parts += f("f/%.1f", it) }
                    if (focal != null && phys != null) {
                        val diag = sqrt((phys.width * phys.width + phys.height * phys.height).toDouble())
                        if (diag > 0) parts += f("%.0f mm equiv.", focal * 43.27 / diag)
                    }
                    if (ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true) parts += "OIS"
                    if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) parts += "Flash"
                    if (3 in caps) parts += "RAW"
                    if (11 in caps) parts += "Multi-camera"
                    parts.joinToString(" · ")
                }
                add("Camera $id · details") {
                    val parts = ArrayList<String>()
                    px?.let { parts += "${it.width}×${it.height}" }
                    phys?.let { parts += f("sensor %.1f×%.1f mm", it.width, it.height) }
                    focal?.let { parts += f("focal %.2f mm", it) }
                    val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL 3"; 4 -> "EXTERNAL"; else -> "?"
                    }
                    parts += "$level API level"
                    ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper }?.let { parts += "$it fps max" }
                    ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { parts += f("%.0f× digital zoom", it) }
                    if (sdk(28)) ch.physicalCameraIds.takeIf { it.isNotEmpty() }?.let { parts += "lenses " + it.joinToString("+") }
                    parts.joinToString(" · ")
                }
            }
        }
    }

    // ------------------------------------------------------------------ AUDIO

    private fun audioType(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV tuner"
        24 -> "Speaker (safe)"
        26, 27 -> "BLE audio"
        else -> "Other ($t)"
    }

    private fun audioStatic(): List<Row> {
        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return rows {
            if (sdk(23)) {
                add("Outputs") {
                    au.getDevices(AudioManager.GET_DEVICES_OUTPUTS).groupBy { audioType(it.type) }
                        .entries.joinToString(", ") { if (it.value.size > 1) "${it.key} ×${it.value.size}" else it.key }
                }
                add("Inputs") {
                    au.getDevices(AudioManager.GET_DEVICES_INPUTS).groupBy { audioType(it.type) }
                        .entries.joinToString(", ") { if (it.value.size > 1) "${it.key} ×${it.value.size}" else it.key }
                }
            }
            opt("Microphones (physical)") {
                if (sdk(28)) au.microphones.let { mics ->
                    "${mics.size}: " + mics.joinToString(", ") { it.description.ifBlank { "mic ${it.id}" } }
                } else null
            }
            add("Native sample rate") { au.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let { "$it Hz" } }
            add("Frames per buffer") { au.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            add("Low-latency audio") { feature("android.hardware.audio.low_latency") }
            add("Pro audio") { feature("android.hardware.audio.pro") }
            opt("Spatial audio") { if (sdk(32)) au.spatializer.immersiveAudioLevel != 0 else null }
        }
    }

    private fun audioLive(): Live {
        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun vol(s: Int) = f("%d / %d", au.getStreamVolume(s), au.getStreamMaxVolume(s))
        val music = au.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max(au.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 1)
        return Live(rows {
            add("Media volume") { vol(AudioManager.STREAM_MUSIC) }
            add("Ring volume") { vol(AudioManager.STREAM_RING) }
            add("Alarm volume") { vol(AudioManager.STREAM_ALARM) }
            add("Call volume") { vol(AudioManager.STREAM_VOICE_CALL) }
            add("Ringer mode") {
                when (au.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "Silent"; AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"; else -> "Normal"
                }
            }
            add("Audio playing") { au.isMusicActive }
            opt("Headphones connected") {
                if (sdk(23)) au.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET
                    )
                } else null
            }
        }, music, "Media volume", "%", 100f)
    }

    // ------------------------------------------------------------------ NETWORK

    private val wifi get() = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val tel get() = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun netTypeName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G (NR)"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        19 -> "4G LTE-CA"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi calling"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "None"
        else -> "Type $t"
    }

    private fun networkStatic(): List<Row> = rows {
        val w = wifi
        add("Wi-Fi") { feature("android.hardware.wifi") }
        if (w != null) {
            add("5 GHz band") { w.is5GHzBandSupported }
            opt("6 GHz band (Wi-Fi 6E)") { if (sdk(30)) w.is6GHzBandSupported else null }
            opt("60 GHz band (WiGig)") { if (sdk(31)) w.is60GHzBandSupported else null }
            opt("Wi-Fi 6 (802.11ax)") { if (sdk(30)) w.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) else null }
            opt("Wi-Fi 7 (802.11be)") { if (sdk(33)) w.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) else null }
        }
        add("Wi-Fi Direct") { feature("android.hardware.wifi.direct") }
        opt("Wi-Fi Aware") { if (sdk(26)) feature("android.hardware.wifi.aware") else null }
        opt("Wi-Fi RTT (indoor positioning)") { if (sdk(28)) feature("android.hardware.wifi.rtt") else null }
        add("Ethernet") { feature("android.hardware.ethernet") }
        add("Cellular radio") { feature("android.hardware.telephony") }
        val t = tel
        if (t != null && feature("android.hardware.telephony")) {
            add("SIM slots / modems") {
                if (sdk(30)) t.activeModemCount else if (sdk(23)) t.phoneCount else 1
            }
            add("Phone type") {
                when (t.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "GSM / LTE"; TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                    TelephonyManager.PHONE_TYPE_SIP -> "SIP"; else -> "None"
                }
            }
            opt("eSIM") { if (sdk(28)) (ctx.getSystemService(Context.EUICC_SERVICE) as? EuiccManager)?.isEnabled else null }
            add("SIM operator") { t.simOperatorName }
            add("Network operator") { "${t.networkOperatorName} (${t.networkCountryIso.uppercase(Locale.US)})" }
        }
    }

    private var prevRx = -1L
    private var prevTx = -1L
    private var prevNetT = 0L

    @Suppress("DEPRECATION")
    private fun networkLive(): Live {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        var down: Float? = null
        var up: Float? = null
        if (prevRx >= 0 && rx >= 0 && now > prevNetT) {
            val s = (now - prevNetT) / 1000f
            down = (rx - prevRx) / 1024f / s
            up = (tx - prevTx) / 1024f / s
        }
        prevRx = rx; prevTx = tx; prevNetT = now
        fun rate(kb: Float) = if (kb > 1024) f("%.2f MB/s", kb / 1024) else f("%.1f KB/s", kb)

        val list = rows {
            if (sdk(23)) {
                val n = cm.activeNetwork
                val caps = n?.let { cm.getNetworkCapabilities(it) }
                add("Connected via") {
                    when {
                        caps == null -> "Not connected"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth tether"
                        else -> "Other"
                    }
                }
                if (caps != null) {
                    add("Internet verified") { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    add("Metered") { !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                    add("Estimated link bandwidth") {
                        f("↓ %.1f / ↑ %.1f Mbps", caps.linkDownstreamBandwidthKbps / 1000.0, caps.linkUpstreamBandwidthKbps / 1000.0)
                    }
                }
                n?.let { cm.getLinkProperties(it) }?.let { lp ->
                    add("IP address") { lp.linkAddresses.map { it.address }.sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull()?.hostAddress }
                    add("DNS") { lp.dnsServers.take(2).joinToString(", ") { it.hostAddress ?: "" } }
                }
            } else {
                add("Connected via") { cm.activeNetworkInfo?.typeName ?: "Not connected" }
            }
            down?.let { d -> add("Download now") { rate(d) } }
            up?.let { u -> add("Upload now") { rate(u) } }
            val w = wifi
            val wi = w?.connectionInfo
            if (w != null && wi != null && (wi.networkId != -1 || (wi.rssi > -127 && wi.linkSpeed > 0))) {
                add("Wi-Fi signal") {
                    val lvl = if (sdk(30)) "${w.calculateSignalLevel(wi.rssi) + 1}/${w.maxSignalLevel + 1}" else "${WifiManager.calculateSignalLevel(wi.rssi, 5)}/4"
                    "${wi.rssi} dBm (bars $lvl)"
                }
                add("Wi-Fi network") { wi.ssid.trim('"').takeIf { it != "<unknown ssid>" } ?: "Hidden (grant location permission)" }
                add("Wi-Fi link speed") { if (sdk(29)) "↓ ${wi.rxLinkSpeedMbps} / ↑ ${wi.txLinkSpeedMbps} Mbps" else "${wi.linkSpeed} Mbps" }
                add("Wi-Fi frequency") { val fr = wi.frequency; f("%d MHz (%s)", fr, if (fr > 5900) "6 GHz" else if (fr > 4900) "5 GHz" else "2.4 GHz") }
                opt("Wi-Fi standard") {
                    if (sdk(30)) when (wi.wifiStandard) {
                        1 -> "Legacy (a/b/g)"; 4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 7 -> "WiGig (ad)"; 8 -> "Wi-Fi 7 (be)"; else -> null
                    } else null
                }
            }
            val t = tel
            if (t != null && feature("android.hardware.telephony")) {
                opt("Mobile data network") { if (sdk(24)) netTypeName(t.dataNetworkType) else null }
                opt("Cell signal") {
                    if (sdk(29)) t.signalStrength?.let { ss ->
                        val dbm = ss.cellSignalStrengths.firstOrNull()?.dbm
                        "level ${ss.level}/4" + (dbm?.takeIf { it != Int.MAX_VALUE }?.let { " · $it dBm" } ?: "")
                    } else if (sdk(28)) t.signalStrength?.let { "level ${it.level}/4" } else null
                }
            }
        }
        return Live(list, down, "Download", "KB/s")
    }

    // ------------------------------------------------------------------ CONNECTIVITY

    private fun btAdapter(): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun connStatic(): List<Row> {
        val ba = btAdapter()
        return rows {
            add("Bluetooth") { feature("android.hardware.bluetooth") }
            add("Bluetooth Low Energy") { feature("android.hardware.bluetooth_le") }
            if (ba != null && sdk(26)) {
                add("LE 2M PHY (BT 5 speed)") { ba.isLe2MPhySupported }
                add("LE Coded PHY (BT 5 range)") { ba.isLeCodedPhySupported }
                add("LE extended advertising") { ba.isLeExtendedAdvertisingSupported }
            }
            opt("LE Audio") { if (sdk(33) && ba != null) ba.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED else null }
            if (ba != null) add("Bluetooth name") { ba.name }
            add("NFC") { feature("android.hardware.nfc") }
            add("NFC payments (HCE)") { feature("android.hardware.nfc.hce") }
            add("USB host (OTG)") { feature("android.hardware.usb.host") }
            add("USB accessory") { feature("android.hardware.usb.accessory") }
            opt("Ultra-wideband (UWB)") { if (sdk(31)) feature("android.hardware.uwb") else null }
        }
    }

    private fun connLive(): Live {
        val ba = btAdapter()
        return Live(rows {
            add("Bluetooth on") { ba?.isEnabled ?: false }
            if (ba != null) add("Paired devices") { ba.bondedDevices.size }
            add("NFC on") { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled ?: "No NFC" }
            add("USB cable connected") {
                val f = IntentFilter("android.hardware.usb.action.USB_STATE")
                val i = if (sdk(33)) app.registerReceiver(null, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(null, f)
                i?.getBooleanExtra("connected", false)
            }
        })
    }

    // ------------------------------------------------------------------ LOCATION

    private val lm get() = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun locationStatic(): List<Row> = rows {
        add("GPS / GNSS receiver") { feature("android.hardware.location.gps") }
        add("Network location") { feature("android.hardware.location.network") }
        add("Providers") { lm.allProviders.joinToString(", ") }
        opt("GNSS chip") { if (sdk(28)) lm.gnssHardwareModelName else null }
        opt("GNSS hardware year") { if (sdk(28)) lm.gnssYearOfHardware.takeIf { it > 0 } else null }
        opt("Raw GNSS measurements") { if (sdk(31)) lm.gnssCapabilities.hasMeasurements() else null }
        opt("Navigation messages") { if (sdk(31)) lm.gnssCapabilities.hasNavigationMessages() else null }
    }

    @Suppress("DEPRECATION")
    private fun locationLive(): Live = Live(rows {
        add("Location services on") {
            if (sdk(28)) lm.isLocationEnabled
            else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
        }
        add("GPS provider on") { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
        add("Last GNSS test") { lastGnss ?: "Run the GPS test in the Tests tab" }
    })

    // ------------------------------------------------------------------ SECURITY

    private fun bioResult(r: Int) = when (r) {
        0 -> "Available & enrolled"
        11 -> "Hardware present, nothing enrolled"
        12 -> "No hardware"
        1 -> "Hardware unavailable"
        15 -> "Security update required"
        else -> "Unknown ($r)"
    }

    @Suppress("DEPRECATION")
    private fun security(): List<Row> = rows {
        add("Fingerprint sensor") { feature("android.hardware.fingerprint") }
        opt("Face unlock hardware") { if (sdk(29)) feature("android.hardware.biometrics.face") else null }
        opt("Iris scanner") { if (sdk(29)) feature("android.hardware.biometrics.iris") else null }
        if (sdk(30)) {
            val bm = ctx.getSystemService(BiometricManager::class.java)
            add("Strong biometrics (class 3)") { bioResult(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) }
            add("Weak biometrics (class 2)") { bioResult(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) }
        } else if (sdk(29)) {
            val bm = ctx.getSystemService(BiometricManager::class.java)
            add("Biometrics") { bioResult(bm.canAuthenticate()) }
        }
        opt("Screen lock set") { if (sdk(23)) (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure else null }
        add("Storage encryption") {
            when ((ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).storageEncryptionStatus) {
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE, 4, 5 -> "Active"
                DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "Inactive"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> "Activating"
                else -> "Unsupported"
            }
        }
        opt("StrongBox secure chip") { if (sdk(28)) feature("android.hardware.strongbox_keystore") else null }
        opt("Hardware keystore") { if (sdk(31)) feature("android.hardware.hardware_keystore") else null }
        opt("Embedded secure element") { if (sdk(28)) feature("android.hardware.se.omapi.ese") else null }
        opt("Digital ID credential hardware") { if (sdk(31)) feature("android.hardware.identity_credential") else null }
    }

    // ------------------------------------------------------------------ OTHER

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (sdk(31)) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun other(): List<Row> = rows {
        val v = vibrator()
        add("Vibration motor") { v?.hasVibrator() ?: false }
        opt("Vibration strength control") { if (sdk(26)) v?.hasAmplitudeControl() else null }
        opt("Vibration motors") { if (sdk(31)) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).vibratorIds.size else null }
        add("Flashlight") { feature("android.hardware.camera.flash") }
        opt("Flashlight brightness levels") {
            if (sdk(33)) {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.cameraIdList.mapNotNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) }
                    .maxOrNull()?.takeIf { it > 1 }
            } else null
        }
        add("Infrared blaster") { (ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() ?: false }
        add("Touchscreen") {
            when {
                feature("android.hardware.touchscreen.multitouch.jazzhand") -> "Yes, 5+ fingers"
                feature("android.hardware.touchscreen.multitouch.distinct") -> "Yes, multi-touch"
                feature("android.hardware.touchscreen") -> "Yes"
                else -> "No"
            }
        }
        opt("Foldable hinge") { if (sdk(30)) sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null else null }
        add("Input devices") {
            InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }
                .filter { !it.isVirtual }
                .joinToString(", ") { d ->
                    val kind = when {
                        d.hasSource(InputDevice.SOURCE_TOUCHSCREEN) -> "touch"
                        d.hasSource(InputDevice.SOURCE_STYLUS) -> "stylus"
                        d.hasSource(InputDevice.SOURCE_JOYSTICK) -> "gamepad"
                        d.hasSource(InputDevice.SOURCE_MOUSE) -> "mouse"
                        d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "keyboard"
                        else -> "buttons"
                    }
                    "${d.name} ($kind)"
                }
        }
    }

    private fun features(): List<Row> {
        val list = pm.systemAvailableFeatures.filter { it.name != null }.sortedBy { it.name }
        return rows {
            add("Features declared") { list.size }
            for (fi in list) add(fi.name.removePrefix("android.hardware.").removePrefix("android.software.")) {
                if (sdk(24) && fi.version > 0) "version ${fi.version}" else "✓"
            }
        }
    }
}

/** Equivalent of InputDevice.supportsSource (API 23+) that works on every version. */
private fun InputDevice.hasSource(source: Int) = (sources and source) == source
