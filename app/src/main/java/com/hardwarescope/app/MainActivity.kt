package com.hardwarescope.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Collections
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val testExec = Executors.newSingleThreadExecutor()
    private lateinit var probe: Probe
    private lateinit var suite: TestSuite
    private lateinit var prefs: SharedPreferences

    private val staticCache = ConcurrentHashMap<Category, List<Row>>()
    private val liveCache = ConcurrentHashMap<Category, List<Row>>()
    private val testResults: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    private val cards = EnumMap<Category, HwCard>(Category::class.java)

    private lateinit var root: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var liveList: LinearLayout
    private lateinit var testsList: LinearLayout
    private lateinit var compareList: LinearLayout
    private lateinit var pages: List<ScrollView>
    private lateinit var tabs: List<TextView>
    private var tab = 0

    private var resumed = false
    private var liveBusy = false
    private var scanning = false
    private var permCallback: (() -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hardwarescope", MODE_PRIVATE)
        probe = Probe(this)
        suite = TestSuite(this, probe)
        goEdgeToEdge()
        setContentView(buildUi())
        scan()
        if (!prefs.getBoolean("askedPerms", false)) ui.postDelayed({ askOptionalPermissions() }, 900)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        syncSensors()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(tick)
        probe.stopSensors()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdownNow(); testExec.shutdownNow()
    }

    // ------------------------------------------------------------------ preferences

    private fun catSet(key: String): MutableSet<String> =
        HashSet(prefs.getStringSet(key, null) ?: Category.values().map { it.name }.toSet())

    private val shown get() = catSet("shown")
    private val liveOn get() = catSet("live")
    private val collapsed get() = HashSet(prefs.getStringSet("collapsed", null) ?: setOf(Category.FEATURES.name))
    private val intervalMs get() = prefs.getInt("interval", 1000).toLong()

    // ------------------------------------------------------------------ window + layout

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (sdk(30)) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (sdk(29)) window.isNavigationBarContrastEnforced = false
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val d = dp(1)
        root = vbox().apply { setBackgroundColor(Pal.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (sdk(30)) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        // Top bar
        val top = hbox().apply { setPadding(d * 16, d * 10, d * 10, d * 8) }
        val titles = vbox()
        titles.addView(label("HardwareScope", 20f, Pal.TEXT, true))
        subtitle = label(deviceTitle(), 12f, Pal.MUTED)
        titles.addView(subtitle)
        top.addView(titles, lp(0, WRAP, 1f))
        top.addView(pill("Select") { showSelectDialog() }, lp(WRAP, WRAP).apply { rightMargin = d * 6 })
        val share = pill("Share ▾") {}
        share.setOnClickListener { showShareMenu(share) }
        top.addView(share, lp(WRAP, WRAP).apply { rightMargin = d * 2 })
        val more = label("⋮", 22f, Pal.TEXT, true).apply { setPadding(d * 10, d * 4, d * 6, d * 4) }
        more.setOnClickListener { showMoreMenu(more) }
        top.addView(more)
        root.addView(top)

        // Pages
        val frame = FrameLayout(this)
        liveList = vbox().apply { setPadding(d * 12, d * 4, d * 12, d * 24) }
        status = label("Scanning hardware…", 13f, Pal.MUTED).apply { setPadding(d * 4, 0, d * 4, d * 10) }
        liveList.addView(status)
        testsList = vbox().apply { setPadding(d * 12, d * 4, d * 12, d * 24) }
        compareList = vbox().apply { setPadding(d * 12, d * 4, d * 12, d * 24) }
        pages = listOf(liveList, testsList, compareList).map { content ->
            ScrollView(this).apply { addView(content); isFillViewport = true; visibility = View.GONE }
        }
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(frame, lp(MATCH, 0, 1f))

        // Bottom tabs
        val bar = hbox().apply { setBackgroundColor(Pal.SURFACE); setPadding(d * 8, d * 6, d * 8, d * 6) }
        tabs = listOf("📊  Live", "🧪  Tests", "⚖️  Compare").mapIndexed { i, t ->
            label(t, 14f, Pal.MUTED, true).apply {
                gravity = Gravity.CENTER; setPadding(0, d * 10, 0, d * 10)
                setOnClickListener { selectTab(i) }
            }
        }
        tabs.forEach { bar.addView(it, lp(0, WRAP, 1f)) }
        root.addView(bar)

        buildTestsPage()
        buildComparePage()
        selectTab(0)
        return root
    }

    private fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) Pal.ACCENT else Pal.MUTED)
            t.background = if (idx == i) rounded(Pal.SURFACE2, dp(12).toFloat()) else null
        }
        if (i == 2) refreshSnapshotChoices()
    }

    // ------------------------------------------------------------------ scanning + live

    private fun scan() {
        if (scanning) return
        scanning = true
        cards.values.forEach { liveList.removeView(it.root) }
        cards.clear()
        staticCache.clear()
        status.text = "Scanning hardware…"
        val all = Category.values()
        bg.execute {
            all.forEachIndexed { idx, c ->
                val rows = probe.static(c)
                staticCache[c] = rows
                ui.post {
                    status.text = "Scanning ${idx + 1}/${all.size}: ${c.title}…"
                    if (c.name in shown) addCard(c)
                }
            }
            ui.post {
                scanning = false
                updateSummary()
            }
        }
    }

    private fun updateSummary() {
        val ram = staticCache[Category.MEMORY]?.firstOrNull()?.value?.substringAfter("sold as ")?.substringBefore(")")
        subtitle.text = listOfNotNull(deviceTitle(), "Android ${android.os.Build.VERSION.RELEASE}", ram).joinToString(" · ")
        status.text = "Detected ${probe.coreCount()} CPU cores, ${probe.sensorCount()} sensors, ${probe.cameraIds().size} cameras · " +
                "showing ${shown.size} of ${Category.values().size} groups · updates every ${intervalMs / 1000.0}s"
    }

    private fun addCard(c: Category) {
        val col = collapsed
        val card = HwCard(this, c, c.name in col) { isCol ->
            val set = collapsed
            if (isCol) set.add(c.name) else set.remove(c.name)
            prefs.edit().putStringSet("collapsed", set).apply()
        }
        card.setStatic(staticCache[c] ?: emptyList())
        card.setLiveEnabled(c.hasLive && c.name in liveOn)
        cards[c] = card
        // keep discovery order
        val order = Category.values().filter { cards.containsKey(it) }
        val pos = order.indexOf(c)
        liveList.addView(card.root, 1 + pos, lp().apply { bottomMargin = dp(10) })
    }

    private fun rebuildCards() {
        cards.values.forEach { liveList.removeView(it.root) }
        cards.clear()
        val s = shown
        val lv = liveOn
        liveCache.keys.retainAll { it.name in lv }
        Category.values().filter { it.name in s && staticCache.containsKey(it) }.forEach { addCard(it) }
        syncSensors()
        updateSummary()
    }

    private fun syncSensors() {
        if (resumed && Category.SENSORS.name in shown && Category.SENSORS.name in liveOn) probe.startSensors() else probe.stopSensors()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!liveBusy && tab == 0) {
                val lv = liveOn
                val cats = cards.keys.filter { it.hasLive && it.name in lv }
                if (cats.isNotEmpty()) {
                    liveBusy = true
                    bg.execute {
                        val res = cats.associateWith { probe.live(it) }
                        ui.post {
                            res.forEach { (c, l) -> liveCache[c] = l.rows; cards[c]?.setLive(l) }
                            liveBusy = false
                        }
                    }
                }
            }
            ui.postDelayed(this, intervalMs)
        }
    }

    // ------------------------------------------------------------------ select dialog

    private fun showSelectDialog() {
        val d = dp(1)
        val s = shown
        val l = liveOn
        val box = vbox().apply { setPadding(d * 20, d * 8, d * 20, d * 8) }
        val head = hbox()
        head.addView(label("Hardware / service", 12f, Pal.MUTED, true), lp(0, WRAP, 1f))
        head.addView(label("Show", 12f, Pal.MUTED, true).apply { gravity = Gravity.CENTER }, lp(d * 56, WRAP))
        head.addView(label("Live", 12f, Pal.MUTED, true).apply { gravity = Gravity.CENTER }, lp(d * 56, WRAP))
        box.addView(head)
        val showBoxes = HashMap<Category, CheckBox>()
        val liveBoxes = HashMap<Category, CheckBox>()
        for (c in Category.values()) {
            val r = hbox().apply { setPadding(0, d * 2, 0, d * 2) }
            r.addView(label("${c.icon}  ${c.title}", 14f), lp(0, WRAP, 1f))
            val sb = CheckBox(this).apply { isChecked = c.name in s }
            val lb = CheckBox(this).apply { isChecked = c.hasLive && c.name in l; isEnabled = c.hasLive; alpha = if (c.hasLive) 1f else 0.3f }
            r.addView(FrameLayout(this).apply { addView(sb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) }, lp(d * 56, WRAP))
            r.addView(FrameLayout(this).apply { addView(lb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) }, lp(d * 56, WRAP))
            showBoxes[c] = sb; liveBoxes[c] = lb
            box.addView(r)
        }
        val quick = hbox().apply { setPadding(0, d * 10, 0, 0) }
        quick.addView(pill("All") { showBoxes.values.forEach { it.isChecked = true }; liveBoxes.values.filter { it.isEnabled }.forEach { it.isChecked = true } }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        quick.addView(pill("None") { showBoxes.values.forEach { it.isChecked = false } }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        quick.addView(pill("Live off") { liveBoxes.values.forEach { it.isChecked = false } })
        box.addView(quick)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Choose what to show")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit()
                    .putStringSet("shown", showBoxes.filter { it.value.isChecked }.keys.map { it.name }.toSet())
                    .putStringSet("live", liveBoxes.filter { it.value.isChecked }.keys.map { it.name }.toSet())
                    .apply()
                rebuildCards()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ menus

    private fun showShareMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Download table (CSV / Excel)")
            menu.add(0, 2, 1, "Download report (HTML)")
            menu.add(0, 3, 2, "Screenshot – visible screen")
            menu.add(0, 4, 3, "Screenshot – full page")
            menu.add(0, 5, 4, "Email report…")
            menu.add(0, 6, 5, "Save snapshot for comparing")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> exportTable(false)
                    2 -> exportTable(true)
                    3 -> screenshot(false)
                    4 -> screenshot(true)
                    5 -> emailDialog()
                    6 -> saveSnapshot()
                }
                true
            }
            show()
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Rescan hardware")
            menu.add(0, 2, 1, "Refresh speed…")
            menu.add(0, 3, 2, "Grant permissions (more detail)")
            menu.add(0, 4, 3, "About")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> scan()
                    2 -> intervalDialog()
                    3 -> askOptionalPermissions(force = true)
                    4 -> dialog("About HardwareScope",
                        "Detects every hardware component Android exposes, tests it, and shows live performance.\n\n" +
                                "Some values (CPU usage, GPU load, chip temperatures) are hidden by newer Android versions or by the manufacturer; " +
                                "the app falls back to the closest available measurement and marks anything it cannot read.\n\n" +
                                "Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}")
                }
                true
            }
            show()
        }
    }

    private fun intervalDialog() {
        val opts = longArrayOf(500, 1000, 2000, 5000)
        val names = arrayOf("0.5 s (fastest)", "1 s", "2 s", "5 s (saves battery)")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Live refresh speed")
            .setSingleChoiceItems(names, opts.indexOf(intervalMs).coerceAtLeast(0)) { dlg, which ->
                prefs.edit().putInt("interval", opts[which].toInt()).apply(); dlg.dismiss(); updateSummary()
            }.show()
    }

    private fun dialog(title: String, msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ permissions

    private fun granted(p: String) = !sdk(23) || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun withPerms(perms: List<String>, then: () -> Unit) {
        val need = perms.filter { !granted(it) }
        if (need.isEmpty() || !sdk(23)) { then(); return }
        permCallback = then
        requestPermissions(need.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            val cb = permCallback; permCallback = null; cb?.invoke()
        }
    }

    private fun askOptionalPermissions(force: Boolean = false) {
        prefs.edit().putBoolean("askedPerms", true).apply()
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (sdk(31)) list += Manifest.permission.BLUETOOTH_CONNECT
        if (list.all { granted(it) }) { if (force) toast("All optional permissions are already granted"); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Show more detail?")
            .setMessage("Android hides some hardware details unless you allow:\n\n• Location – Wi-Fi network name & GPS satellites\n• Phone – mobile network type (4G/5G)\n• Nearby devices – Bluetooth name & paired devices\n\nNothing leaves your phone unless you share or email a report.")
            .setPositiveButton("Allow") { _, _ -> withPerms(list) { scan() } }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ------------------------------------------------------------------ snapshot / export

    private fun currentSnapshot(): Snapshot {
        val s = shown
        val rows = ArrayList<SRow>()
        for (c in Category.values()) {
            if (c.name !in s) continue
            staticCache[c]?.forEach { rows += SRow(c.title, it.name, it.value) }
            liveCache[c]?.forEach { rows += SRow(c.title, it.name, it.value) }
        }
        synchronized(testResults) { testResults.forEach { (k, v) -> rows += SRow("Test results", k, v) } }
        return Snapshot(deviceTitle(), System.currentTimeMillis(), rows)
    }

    private fun legacyStorage(then: () -> Unit) =
        if (!sdk(29) && sdk(23)) withPerms(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), then) else then()

    private fun exportTable(html: Boolean) = legacyStorage {
        val snap = currentSnapshot()
        val name = Reports.baseName(snap.device) + if (html) ".html" else ".csv"
        val bytes = (if (html) Reports.html(snap) else Reports.csv(snap)).toByteArray()
        try {
            val where = Reports.savePublic(this, name, if (html) "text/html" else "text/csv", false) { it.write(bytes) }
            savedDialog("Saved ${snap.rows.size} rows to\n$where", Reports.shareable(this, name, bytes), if (html) "text/html" else "text/csv")
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    private fun capturePage(full: Boolean) = if (full) Reports.capture(pages[tab].getChildAt(0), Pal.BG) else Reports.capture(root, Pal.BG)

    private fun screenshot(full: Boolean) = legacyStorage {
        try {
            val bmp = capturePage(full)
            val name = Reports.baseName(deviceTitle()) + ".png"
            val where = Reports.savePublic(this, name, "image/png", true) { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = Reports.shareable(this, name, bmp)
            bmp.recycle()
            savedDialog("Screenshot saved to\n$where", uri, "image/png")
        } catch (e: Throwable) {
            toast("Screenshot failed: ${e.message}")
        }
    }

    private fun savedDialog(msg: String, uri: Uri, mime: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Saved")
            .setMessage(msg)
            .setPositiveButton("Share") { _, _ -> share(listOf(uri), mime, null) }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun share(uris: List<Uri>, mime: String, subject: String?, body: String? = null, emailOnly: Boolean = false) {
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = mime
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        body?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        if (emailOnly) {
            val mail = Intent(intent).apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
            try {
                startActivity(Intent.createChooser(mail, "Send report with…")); return
            } catch (_: ActivityNotFoundException) {
            }
        }
        try {
            startActivity(Intent.createChooser(intent, "Share with…"))
        } catch (e: ActivityNotFoundException) {
            toast("No app available to share")
        }
    }

    private fun emailDialog() {
        val items = arrayOf("Data table (CSV – opens in Excel)", "Formatted report (HTML)", "Screenshot (full page)", "Snapshot file (for comparing on another phone)")
        val checked = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Attach to email")
            .setMultiChoiceItems(items, checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("Compose") { _, _ ->
                try {
                    val snap = currentSnapshot()
                    val base = Reports.baseName(snap.device)
                    val uris = ArrayList<Uri>()
                    if (checked[0]) uris += Reports.shareable(this, "$base.csv", Reports.csv(snap).toByteArray())
                    if (checked[1]) uris += Reports.shareable(this, "$base.html", Reports.html(snap).toByteArray())
                    if (checked[2]) {
                        val bmp = capturePage(true); uris += Reports.shareable(this, "$base.png", bmp); bmp.recycle()
                    }
                    if (checked[3]) uris += Reports.shareable(this, "$base.json", Reports.toJson(snap).toByteArray())
                    if (uris.isEmpty()) { toast("Nothing selected"); return@setPositiveButton }
                    val tests = synchronized(testResults) { testResults.entries.joinToString("\n") { "• ${it.key}: ${it.value}" } }
                    val body = "Hardware report for ${snap.device} (Android ${android.os.Build.VERSION.RELEASE}).\n\n" +
                            (if (tests.isNotEmpty()) "Test results:\n$tests\n\n" else "") + "Generated by HardwareScope."
                    share(uris, "*/*", "Hardware report – ${snap.device}", body, emailOnly = true)
                } catch (e: Throwable) {
                    toast("Could not prepare email: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ tests page

    private val testResultViews = HashMap<String, TextView>()
    private val testButtons = HashMap<String, TextView>()

    private fun buildTestsPage() {
        val d = dp(1)
        val head = hbox().apply { setPadding(d * 4, 0, d * 4, d * 12) }
        head.addView(label("Benchmarks and functional checks. Results are added to exports and emails.", 13f, Pal.MUTED), lp(0, WRAP, 1f))
        testsList.addView(head)
        val actions = hbox().apply { setPadding(d * 4, 0, 0, d * 12) }
        actions.addView(pill("▶  Run all automatic", true) { runAll() }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        actions.addView(pill("Clear results") {
            testResults.clear(); testResultViews.values.forEach { it.text = "Not run yet"; it.setTextColor(Pal.MUTED) }
        })
        testsList.addView(actions)
        for (t in suite.tests) {
            val card = vbox().apply {
                background = rounded(Pal.SURFACE, d * 14f, d, Pal.BORDER); setPadding(d * 14, d * 12, d * 14, d * 12)
            }
            val top = hbox()
            val texts = vbox()
            val tag = when (t.kind) { Kind.AUTO -> ""; Kind.MANUAL -> "  · you check"; Kind.INTERACTIVE -> "  · hands-on" }
            texts.addView(label(t.name + tag, 15f, Pal.TEXT, true))
            texts.addView(label(t.desc, 12f, Pal.MUTED).apply { setPadding(0, d * 2, 0, 0) })
            top.addView(texts, lp(0, WRAP, 1f).apply { rightMargin = d * 10 })
            val btn = pill("Run") { runTest(t) }
            top.addView(btn)
            card.addView(top)
            val res = label("Not run yet", 13f, Pal.MUTED).apply { setPadding(0, d * 8, 0, 0) }
            card.addView(res)
            testResultViews[t.id] = res; testButtons[t.id] = btn
            testsList.addView(card, lp().apply { bottomMargin = d * 10 })
        }
    }

    private fun setResult(t: TestDef, text: String, done: Boolean) {
        testResultViews[t.id]?.apply {
            this.text = text
            setTextColor(
                when {
                    !done -> Pal.BLUE
                    text.contains("FAIL") || text.startsWith("Error") || text.startsWith("Permission") -> Pal.BAD
                    text.startsWith("No ") || text.startsWith("Could not") || text.startsWith("Requires") -> Pal.WARN
                    else -> Pal.ACCENT
                }
            )
        }
        if (done) {
            testResults[t.name] = text
            testButtons[t.id]?.apply { this.text = "Run"; isEnabled = true; alpha = 1f }
        }
    }

    private fun runTest(t: TestDef, next: (() -> Unit)? = null) {
        when (t.id) {
            "touch" -> { touchTest(t); return }
            "pixels" -> { pixelTest(t); return }
        }
        withPerms(t.perms) {
            if (t.perms.any { !granted(it) }) { setResult(t, "Permission denied – allow it in Settings to run this test", true); next?.invoke(); return@withPerms }
            testButtons[t.id]?.apply { text = "…"; isEnabled = false; alpha = 0.6f }
            setResult(t, "Starting…", false)
            testExec.execute {
                val r = try {
                    t.run!!.invoke { p -> ui.post { setResult(t, p, false) } }
                } catch (e: Throwable) {
                    "Error: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                }
                ui.post {
                    val q = t.confirm
                    if (q != null && !r.startsWith("Error") && !r.startsWith("No ") && !r.startsWith("Requires")) {
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(t.name).setMessage(q).setCancelable(false)
                            .setPositiveButton("Yes") { _, _ -> setResult(t, "PASS · $r", true); next?.invoke() }
                            .setNegativeButton("No") { _, _ -> setResult(t, "FAIL · $r", true); next?.invoke() }
                            .show()
                    } else {
                        setResult(t, r, true); next?.invoke()
                    }
                }
            }
        }
    }

    private fun runAll() {
        val queue = suite.tests.filter { it.kind == Kind.AUTO }
        fun step(i: Int) {
            if (i >= queue.size) { toast("All automatic tests finished"); return }
            runTest(queue[i]) { step(i + 1) }
        }
        step(0)
    }

    private fun fullScreenDialog(content: View): Dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setContentView(content)
    }

    private fun touchTest(t: TestDef) {
        val v = TouchTestView(this)
        val dlg = fullScreenDialog(v)
        dlg.setOnDismissListener {
            val m = v.maxPointers
            setResult(t, if (m == 0) "No touches recorded" else "Max $m simultaneous touches detected", true)
        }
        dlg.show()
    }

    private fun pixelTest(t: TestDef) {
        var dlg: Dialog? = null
        val v = ColorTestView(this) {
            dlg?.dismiss()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t.name).setMessage("Did you notice any dead, stuck or discoloured pixels?")
                .setPositiveButton("Yes, I saw some") { _, _ -> setResult(t, "FAIL · user saw defective pixels", true) }
                .setNegativeButton("No, all clean") { _, _ -> setResult(t, "PASS · no dead or stuck pixels seen", true) }
                .show()
        }
        val created = fullScreenDialog(v)
        dlg = created
        created.show()
    }

    // ------------------------------------------------------------------ compare page

    private val snapDir get() = File(filesDir, "snapshots").apply { mkdirs() }
    private lateinit var spinA: Spinner
    private lateinit var spinB: Spinner
    private lateinit var onlyDiff: CheckBox
    private lateinit var compareResult: LinearLayout
    private var choices: List<Pair<String, () -> Snapshot>> = emptyList()
    private var lastCompare: Triple<Snapshot, Snapshot, List<Reports.Diff>>? = null

    private fun buildComparePage() {
        val d = dp(1)
        compareList.addView(label("Save a snapshot of this phone, import one from another phone, or compare against an earlier run (e.g. before/after an update, or new vs. used device).", 13f, Pal.MUTED).apply {
            setPadding(d * 4, 0, d * 4, d * 12)
        })
        val row1 = hbox()
        row1.addView(pill("Save snapshot", true) { saveSnapshot() }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        row1.addView(pill("Import file") { importSnapshot() }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        row1.addView(pill("Send…") { sendSnapshot() }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        row1.addView(pill("Delete…") { deleteSnapshot() })
        compareList.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row1) }, lp().apply { bottomMargin = d * 14 })

        fun spinner(): Spinner = Spinner(this).apply { background = rounded(Pal.SURFACE2, d * 10f, d, Pal.BORDER); setPadding(d * 8, d * 4, d * 8, d * 4) }
        compareList.addView(label("Compare", 12f, Pal.MUTED, true))
        spinA = spinner(); compareList.addView(spinA, lp().apply { topMargin = d * 4; bottomMargin = d * 8 })
        compareList.addView(label("with", 12f, Pal.MUTED, true))
        spinB = spinner(); compareList.addView(spinB, lp().apply { topMargin = d * 4; bottomMargin = d * 8 })
        onlyDiff = CheckBox(this).apply { text = "Only show differences"; setTextColor(Pal.TEXT); isChecked = true }
        compareList.addView(onlyDiff)
        val row2 = hbox().apply { setPadding(0, d * 6, 0, d * 12) }
        row2.addView(pill("⚖️  Compare", true) { runCompare() }, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        row2.addView(pill("Export comparison") { exportCompare() })
        compareList.addView(row2)
        compareResult = vbox()
        compareList.addView(compareResult)
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(Pal.TEXT)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinA.onItemSelectedListener = listener
        spinB.onItemSelectedListener = listener
    }

    private fun snapshotFiles() = snapDir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun refreshSnapshotChoices() {
        val list = ArrayList<Pair<String, () -> Snapshot>>()
        list += "This phone – live now" to { currentSnapshot() }
        for (f in snapshotFiles()) {
            val snap = try { Reports.fromJson(f.readText()) } catch (e: Exception) { continue }
            list += snap.label to { snap }
        }
        val selA = spinA.selectedItemPosition
        val selB = spinB.selectedItemPosition
        choices = list
        val labels = list.map { it.first }
        spinA.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinB.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinA.setSelection(if (selA in labels.indices) selA else 0)
        spinB.setSelection(if (selB in labels.indices && selB > 0) selB else if (labels.size > 1) 1 else 0)
    }

    private fun saveSnapshot() {
        val snap = currentSnapshot()
        val name = Reports.baseName(snap.device) + ".json"
        File(snapDir, name).writeText(Reports.toJson(snap))
        toast("Snapshot saved (${snap.rows.size} items). Find it in the Compare tab.")
        if (tab == 2) refreshSnapshotChoices()
    }

    private fun importSnapshot() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, 7) } catch (e: ActivityNotFoundException) { toast("No file picker available") }
    }

    @Deprecated("Framework Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val snap = Reports.fromJson(text)
            File(snapDir, Reports.baseName(snap.device) + "_imported.json").writeText(text)
            toast("Imported ${snap.label}")
            refreshSnapshotChoices()
        } catch (e: Exception) {
            toast("Not a valid HardwareScope snapshot")
        }
    }

    private fun sendSnapshot() {
        val files = snapshotFiles()
        if (files.isEmpty()) { toast("Save a snapshot first"); return }
        val labels = files.map { f -> try { Reports.fromJson(f.readText()).label } catch (e: Exception) { f.name } }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Send which snapshot?")
            .setItems(labels) { _, i ->
                val uri = Reports.shareable(this, files[i].name, files[i].readBytes())
                share(listOf(uri), "application/json", "HardwareScope snapshot – ${labels[i]}",
                    "Open this file in HardwareScope › Compare › Import file to compare phones.")
            }.show()
    }

    private fun deleteSnapshot() {
        val files = snapshotFiles()
        if (files.isEmpty()) { toast("No saved snapshots"); return }
        val labels = files.map { f -> try { Reports.fromJson(f.readText()).label } catch (e: Exception) { f.name } }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Delete snapshot")
            .setItems(labels) { _, i -> files[i].delete(); refreshSnapshotChoices(); toast("Deleted") }.show()
    }

    private fun runCompare() {
        if (choices.size < 2) { toast("Save or import at least one snapshot first"); return }
        val a = choices.getOrNull(spinA.selectedItemPosition)?.second?.invoke() ?: return
        val b = choices.getOrNull(spinB.selectedItemPosition)?.second?.invoke() ?: return
        val diffs = Reports.compare(a, b)
        lastCompare = Triple(a, b, diffs)
        val d = dp(1)
        compareResult.removeAllViews()
        val nDiff = diffs.count { it.different }
        compareResult.addView(label("$nDiff of ${diffs.size} items differ", 14f, Pal.TEXT, true).apply { setPadding(d * 4, 0, 0, d * 8) })
        val header = hbox().apply { setPadding(d * 4, d * 6, d * 4, d * 6); background = rounded(Pal.SURFACE2, d * 8f) }
        header.addView(label("Item", 11f, Pal.MUTED, true), lp(0, WRAP, 0.34f))
        header.addView(label("A: " + a.device, 11f, Pal.MUTED, true), lp(0, WRAP, 0.28f))
        header.addView(label("B: " + b.device, 11f, Pal.MUTED, true), lp(0, WRAP, 0.28f))
        header.addView(label("Δ", 11f, Pal.MUTED, true).apply { gravity = Gravity.END }, lp(0, WRAP, 0.10f))
        compareResult.addView(header)
        var lastCat = ""
        for (x in diffs) {
            if (onlyDiff.isChecked && !x.different) continue
            if (x.cat != lastCat) {
                lastCat = x.cat
                compareResult.addView(label(x.cat, 13f, Pal.ACCENT, true).apply { setPadding(d * 4, d * 12, 0, d * 4) })
            }
            val r = hbox().apply { gravity = Gravity.TOP; setPadding(d * 4, d * 5, d * 4, d * 5) }
            val c = if (x.different) Pal.WARN else Pal.TEXT
            r.addView(label(x.name, 12f, Pal.MUTED), lp(0, WRAP, 0.34f).apply { rightMargin = d * 6 })
            r.addView(label(x.a, 12f, c), lp(0, WRAP, 0.28f).apply { rightMargin = d * 6 })
            r.addView(label(x.b, 12f, c), lp(0, WRAP, 0.28f))
            r.addView(label(x.delta, 11f, if (x.delta.startsWith("-")) Pal.BAD else Pal.ACCENT).apply { gravity = Gravity.END }, lp(0, WRAP, 0.10f))
            compareResult.addView(r)
            compareResult.addView(View(this).apply { setBackgroundColor(Pal.BORDER) }, ViewGroup.LayoutParams(MATCH, 1))
        }
    }

    private fun exportCompare() {
        val c = lastCompare ?: run { toast("Run a comparison first"); return }
        val name = "HardwareScope_compare_" + Reports.baseName(c.first.device).substringAfter("HardwareScope_") + ".csv"
        val bytes = Reports.compareCsv(c.first, c.second, c.third).toByteArray()
        legacyStorage {
            try {
                val where = Reports.savePublic(this, name, "text/csv", false) { it.write(bytes) }
                savedDialog("Comparison saved to\n$where", Reports.shareable(this, name, bytes), "text/csv")
            } catch (e: Exception) {
                toast("Could not save: ${e.message}")
            }
        }
    }
}
