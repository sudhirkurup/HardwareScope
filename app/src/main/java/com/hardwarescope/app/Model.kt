package com.hardwarescope.app

import android.os.Build
import java.io.File
import java.util.Locale

/** Hardware groups, listed in the order they are discovered and shown on screen. */
enum class Category(val title: String, val icon: String, val hasLive: Boolean) {
    SYSTEM("Device & OS", "📱", true),
    CPU("Processor (CPU)", "🧠", true),
    GPU("Graphics (GPU)", "🎮", true),
    MEMORY("Memory (RAM)", "💾", true),
    STORAGE("Storage", "🗄️", true),
    BATTERY("Battery & Power", "🔋", true),
    THERMAL("Thermal", "🌡️", true),
    DISPLAY("Display", "🖥️", true),
    SENSORS("Sensors", "🧭", true),
    CAMERA("Cameras", "📷", false),
    AUDIO("Audio", "🔊", true),
    NETWORK("Wi-Fi & Cellular", "📶", true),
    CONNECTIVITY("Bluetooth, NFC & USB", "🔗", true),
    LOCATION("Location / GNSS", "📍", true),
    SECURITY("Biometrics & Security", "🔐", false),
    OTHER("Haptics, Flash, IR & Input", "✨", false),
    FEATURES("All System Features", "🧩", false);
}

data class Row(val name: String, val value: String)

/** One live refresh of a category: rows plus an optional value for the sparkline graph. */
data class Live(
    val rows: List<Row>,
    val spark: Float? = null,
    val sparkLabel: String = "",
    val sparkUnit: String = "",
    val sparkMax: Float? = null
)

/** Collects rows; any failure (missing API on older Android, missing permission) becomes a readable value. */
class Rows {
    val list = ArrayList<Row>()
    fun add(name: String, block: () -> Any?) {
        val v = try {
            when (val r = block()) {
                null -> "N/A"
                is Boolean -> if (r) "Yes" else "No"
                is String -> if (r.isBlank()) "N/A" else r
                else -> r.toString()
            }
        } catch (e: SecurityException) {
            "Permission needed"
        } catch (e: Throwable) {
            "N/A"
        }
        list.add(Row(name, v))
    }

    /** Adds the row only when the value is available. */
    fun opt(name: String, block: () -> Any?) {
        val before = list.size
        add(name, block)
        if (list.size > before && list.last().value == "N/A") list.removeAt(list.size - 1)
    }
}

inline fun rows(b: Rows.() -> Unit): List<Row> = Rows().apply(b).list

fun sdk(v: Int) = Build.VERSION.SDK_INT >= v

fun f(fmt: String, vararg args: Any?): String = String.format(Locale.US, fmt, *args)

fun readText(path: String): String? = try {
    File(path).readText().trim()
} catch (e: Throwable) {
    null
}

fun readLong(path: String): Long? = readText(path)?.trim()?.split(" ", "\t", "\n")?.firstOrNull()?.toLongOrNull()

fun fmtBytes(b: Long): String {
    if (b < 0) return "N/A"
    val kb = 1024.0
    return when {
        b >= kb * kb * kb * kb -> f("%.2f TB", b / (kb * kb * kb * kb))
        b >= kb * kb * kb -> f("%.2f GB", b / (kb * kb * kb))
        b >= kb * kb -> f("%.1f MB", b / (kb * kb))
        b >= kb -> f("%.1f KB", b / kb)
        else -> "$b B"
    }
}

fun fmtKhz(khz: Long): String = if (khz >= 1_000_000) f("%.2f GHz", khz / 1_000_000.0) else "${khz / 1000} MHz"

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (d > 0) f("%dd %02dh %02dm", d, h, m) else f("%02d:%02d:%02d", h, m, sec)
}

fun String.cap(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

fun deviceTitle(): String {
    val man = Build.MANUFACTURER.cap()
    val model = Build.MODEL
    return if (model.startsWith(man, ignoreCase = true)) model else "$man $model"
}
