package com.example.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val BG = 0xFF0F1715.toInt()
    private val CARD = 0xFF1A2420.toInt()
    private val GREEN = 0xFF0F6B4B.toInt()
    private val MINT = 0xFF34D399.toInt()
    private val MUTED = 0xFF8A9A94.toInt()
    private val NAV = 0xFF121C18.toInt()
    private val AMBER = 0xFFF59E0B.toInt()
    private val navIcons = listOf(R.drawable.ic_game, R.drawable.ic_keyboard, R.drawable.ic_settings)
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private lateinit var chipMouse: TextView
    private lateinit var chipKb: TextView
    private lateinit var chipSvc: TextView
    private var tab = 0

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { updateStatus() }
        override fun onInputDeviceRemoved(id: Int) { updateStatus() }
        override fun onInputDeviceChanged(id: Int) { updateStatus() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            var l = 0
            var r = 0
            if (Build.VERSION.SDK_INT >= 28) {
                val c = ins.displayCutout
                if (c != null) { l = c.safeInsetLeft; r = c.safeInsetRight }
            }
            v.setPadding(l, 0, r, 0)
            ins
        }
        root.addView(topBar())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(M, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)
        select(0)

        ShizukuBridge.init()
        val refresh = { runOnUiThread { if (tab == 2) select(2) else updateStatus() } }
        ShizukuBridge.onChange = refresh
        Injector.onChange = refresh
        PairHelper.onChange = refresh
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        Injector.autoStart(this, null)
        select(tab)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
    }

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun icon(res: Int, color: Int, sizeDp: Int): Drawable {
        val d = getDrawable(res)!!.mutate()
        d.setTint(color)
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        return d
    }

    private fun TextView.setIcon(res: Int, color: Int, sizeDp: Int) {
        setCompoundDrawables(icon(res, color, sizeDp), null, null, null)
        compoundDrawablePadding = dp(8)
    }

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = rounded(color, 20)
        setOnClickListener { onClick() }
    }

    private fun hasMouse(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { d -> (d.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE } ?: false
    }

    private fun hasKeyboard(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { d -> !d.isVirtual && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } ?: false
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openWirelessDebug() {
        try {
            startActivity(Intent("android.settings.ADB_WIFI_SETTINGS"))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (e2: Exception) { toast("Buka Opsi pengembang secara manual") }
        }
    }

    // ---------- top bar & nav ----------
    private fun chip() = TextView(this).apply {
        textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(6), dp(12), dp(6))
        maxLines = 1
    }

    private fun setChip(tv: TextView, text: String, ok: Boolean) {
        tv.text = text
        tv.setIcon(if (ok) R.drawable.ic_dot else R.drawable.ic_ring, if (ok) MINT else MUTED, 9)
        tv.setTextColor(if (ok) Color.WHITE else MUTED)
        tv.background = rounded(if (ok) GREEN else CARD, 20)
    }

    private fun updateStatus() {
        setChip(chipMouse, "Mouse", hasMouse())
        setChip(chipKb, "Keyboard", hasKeyboard())
        setChip(chipSvc, if (Injector.ready) "Terhubung" else "Belum terhubung", Injector.ready)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        val logo = TextView(this).apply {
            text = "V"; textSize = 20f; setTextColor(MINT); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFF12201B.toInt()); setStroke(dp(3), MINT)
            }
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(label("Vortex Mouse Pro", 17f, Color.WHITE, true).apply { maxLines = 1 })
        titles.addView(label("Keyboard dan mouse untuk game", 11f, MUTED, false).apply { maxLines = 1 })
        bar.addView(titles, LinearLayout.LayoutParams(0, W, 1f))
        chipMouse = chip(); chipKb = chip(); chipSvc = chip()
        listOf(chipMouse, chipKb, chipSvc).forEach {
            bar.addView(it, LinearLayout.LayoutParams(W, W).apply { leftMargin = dp(8) })
        }
        return bar
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(NAV) }
        val names = listOf("Mulai Game", "Pemetaan", "Pengaturan")
        navItems = names.mapIndexed { i, n ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { select(i) }
                addView(ImageView(context).apply { setImageResource(navIcons[i]) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(context).apply {
                    text = n; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), 0, 0, 0)
                })
            }
        }
        navItems.forEach { nav.addView(it, LinearLayout.LayoutParams(0, W, 1f)) }
        return nav
    }

    private fun select(i: Int) {
        tab = i
        navItems.forEachIndexed { j, t ->
            val c = if (j == i) Color.WHITE else MUTED
            (t.getChildAt(0) as ImageView).setColorFilter(c)
            (t.getChildAt(1) as TextView).setTextColor(c)
            t.setBackgroundColor(if (j == i) GREEN else NAV)
        }
        content.removeAllViews()
        val v = when (i) { 0 -> gamesView(); 1 -> mapView(); else -> settingsView() }
        content.addView(v, FrameLayout.LayoutParams(M, M))
        updateStatus()
    }

    // ---------- tab: game ----------
    private fun gamesView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill("Tambah Game", CARD) { addGame() }.also { it.setIcon(R.drawable.ic_add, MINT, 16) },
            LinearLayout.LayoutParams(W, W).apply { setMargins(dp(20), dp(4), 0, dp(4)) })
        if (!Injector.ready) {
            top.addView(pill("Belum terhubung - pilih cara sambung", 0xFF7A4B06.toInt()) { select(2) }
                .also { it.setIcon(R.drawable.ic_warning, AMBER, 16) },
                LinearLayout.LayoutParams(W, W).apply { setMargins(dp(10), dp(4), 0, dp(4)) })
        }
        col.addView(top)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        val games = Store.games(this)
        if (games.isEmpty()) {
            row.addView(label("Belum ada game.\nTap \"Tambah Game\" untuk memilih game dari aplikasi yang terpasang.", 14f, MUTED, false))
        }
        games.forEach { pkg ->
            gameCard(pkg)?.let {
                row.addView(it, LinearLayout.LayoutParams(dp(180), M).apply { rightMargin = dp(14) })
            }
        }
        scroll.addView(row, FrameLayout.LayoutParams(W, M))
        col.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))
        return col
    }

    private fun gameCard(pkg: String): View? {
        val pm = packageManager
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return null }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, 18)
            setOnClickListener { launchGame(pkg) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Hapus dari daftar?")
                    .setPositiveButton("Hapus") { _, _ -> Store.saveGames(this@MainActivity, Store.games(this@MainActivity) - pkg); select(0) }
                    .setNegativeButton("Batal", null).show()
                true
            }
        }
        val icon = ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(info)) }
        card.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(12) })
        card.addView(label(pm.getApplicationLabel(info).toString(), 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(8), dp(8), dp(8), 0)
        })
        card.addView(View(this), LinearLayout.LayoutParams(M, 0, 1f))
        card.addView(pill("Mulai Game", GREEN) { launchGame(pkg) },
            LinearLayout.LayoutParams(M, W).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        return card
    }

    private fun addGame() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val have = Store.games(this)
        val apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in have }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (apps.isEmpty()) { toast("Tidak ada aplikasi yang bisa ditambahkan"); return }
        val adapter = object : ArrayAdapter<ResolveInfo>(this, 0, apps) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val r = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val item = getItem(pos)!!
                r.addView(ImageView(context).apply { setImageDrawable(item.loadIcon(pm)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                r.addView(TextView(context).apply {
                    text = item.loadLabel(pm); setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0)
                })
                return r
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pilih game")
            .setAdapter(adapter) { _, i ->
                Store.saveGames(this, have + apps[i].activityInfo.packageName); select(0)
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun startGame(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Game tidak bisa dibuka"); return }
        startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        startActivity(i)
    }

    private fun launchGame(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan 'Tampilkan di atas aplikasi lain' dulu"); select(2); return
        }
        if (Injector.ready) { startGame(pkg); return }
        toast("Menyambung...")
        Injector.autoStart(this) { err ->
            runOnUiThread {
                if (err == null) startGame(pkg)
                else { toast(err); select(2) }
            }
        }
    }

    // ---------- tab: pemetaan ----------
    private fun fn(kind: String) = when (kind) {
        "TAP" -> "Tombol"; "UP" -> "Joystick atas"; "DOWN" -> "Joystick bawah"
        "LEFT" -> "Joystick kiri"; "LOOK" -> "FPS Look"; else -> "Joystick kanan"
    }

    private fun mapView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
        col.addView(label("Pemetaan tombol", 18f, Color.WHITE, true))
        col.addView(label("Di dalam game: tap bubble V, pilih Edit tombol, lalu Template atau Tambah.", 12f, MUTED, false))
        val maps = Store.load(this)
        if (maps.isEmpty()) col.addView(label("\nBelum ada pemetaan.", 14f, MUTED, false))
        maps.forEach { m ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 14); setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            r.addView(label("${keyName(m.keyCode)}  >  ${fn(m.kind)}${if (m.kind == "TAP") " - " + m.mode else ""}  (${m.x.toInt()}, ${m.y.toInt()})", 14f, Color.WHITE, false),
                LinearLayout.LayoutParams(0, W, 1f))
            r.addView(pill("Hapus", 0xFF5B2A2A.toInt()) {
                Store.save(this, Store.load(this).filter { it.keyCode != m.keyCode }); select(1)
            })
            col.addView(r, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) })
        }
        return ScrollView(this).apply { addView(col) }
    }

    // ---------- tab: pengaturan ----------
    private fun connectDone(err: String?) {
        runOnUiThread {
            toast(if (err == null) "Terhubung lewat ${Injector.mode}. Siap main." else "Gagal: $err")
            select(2)
        }
    }

    private fun optionCard(title: String, status: String, ok: Boolean, desc: String, buttons: List<View>): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(label(title, 15f, Color.WHITE, true).also {
            it.setIcon(if (ok) R.drawable.ic_check else R.drawable.ic_warning, if (ok) MINT else AMBER, 18)
        })
        c.addView(label(status, 12f, if (ok) MINT else AMBER, true).apply { setPadding(0, dp(4), 0, 0) })
        c.addView(label(desc, 12f, MUTED, false).apply { setPadding(0, dp(6), 0, dp(8)) })
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            br.addView(b, LinearLayout.LayoutParams(W, W).apply { if (i > 0) leftMargin = dp(8) })
        }
        c.addView(br)
        return c
    }

    private fun adbCard(): View {
        val on = Injector.ready && Injector.mode == "ADB"
        val status = when {
            on -> "Tersambung"
            PairHelper.status.isNotEmpty() -> PairHelper.status
            AdbBackend.paired(this) -> "Sudah dipasangkan. Tap Sambungkan."
            else -> "Belum dipasangkan"
        }
        val btns = listOf(
            pill("Mulai Pairing", GREEN) {
                PairHelper.start(this); openWirelessDebug()
            },
            pill("Sambungkan", 0xFF1F3D33.toInt()) {
                toast("Menyambung...")
                Injector.connect(this, "ADB") { connectDone(it) }
            })
        return optionCard("Pairing Debug Nirkabel", status, on,
            "Tanpa aplikasi lain. Tap Mulai Pairing, di layar Debugging nirkabel pilih Pair device with pairing code, lalu tarik notifikasi dan ketik kodenya.",
            btns)
    }

    private fun shizukuCard(): View {
        val s = ShizukuBridge.state(this)
        val on = Injector.ready && Injector.mode == "Shizuku"
        val status = when {
            on -> "Tersambung"
            s == 0 -> "Shizuku belum terpasang"
            s == 1 -> "Shizuku belum jalan"
            s == 2 -> "Perlu izin Shizuku"
            else -> "Shizuku siap. Tap Sambungkan."
        }
        val main = when (s) {
            0 -> pill("Unduh Shizuku", GREEN) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
            1 -> pill("Buka Shizuku", GREEN) {
                val i = packageManager.getLaunchIntentForPackage(ShizukuBridge.PKG)
                if (i != null) startActivity(i) else toast("Buka aplikasi Shizuku dari layar utama")
            }
            2 -> pill("Izinkan", GREEN) { ShizukuBridge.requestPermission() }
            else -> pill("Sambungkan", GREEN) {
                toast("Menyambung...")
                Injector.connect(this, "Shizuku") { connectDone(it) }
            }
        }
        return optionCard("Aktifkan lewat Shizuku", status, on,
            "Nyalakan Shizuku (debug nirkabel atau root), lalu izinkan aplikasi ini.", listOf(main))
    }

    private fun settingsView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
        val gap = { LinearLayout.LayoutParams(M, W).apply { topMargin = dp(10) } }

        val on = Injector.ready
        val big = label(if (on) "Terhubung lewat ${Injector.mode}. Siap main." else "Belum terhubung. Pilih salah satu cara di bawah.",
            14f, Color.WHITE, true).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(if (on) GREEN else CARD, 14)
        }
        big.setIcon(if (on) R.drawable.ic_check else R.drawable.ic_warning, if (on) MINT else AMBER, 18)
        col.addView(big)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row.addView(adbCard(), LinearLayout.LayoutParams(0, M, 1f).apply { rightMargin = dp(5) })
        row.addView(shizukuCard(), LinearLayout.LayoutParams(0, M, 1f).apply { leftMargin = dp(5) })
        col.addView(row, gap())

        val overlayOk = Settings.canDrawOverlays(this)
        val ov = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val ovT = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ovT.addView(label("Tampilkan di atas aplikasi lain", 14f, Color.WHITE, true).also {
            it.setIcon(if (overlayOk) R.drawable.ic_check else R.drawable.ic_warning, if (overlayOk) MINT else AMBER, 18)
        })
        ovT.addView(label("Untuk bubble, tombol di layar, dan menangkap keyboard/mouse.", 12f, MUTED, false))
        ov.addView(ovT, LinearLayout.LayoutParams(0, W, 1f))
        ov.addView(pill(if (overlayOk) "Aktif" else "Izinkan", GREEN) { openOverlaySettings() })
        col.addView(ov, gap())

        val sw = Switch(this).apply {
            isChecked = OverlayService.instance?.isBubbleShown() == true
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) { toast("Izinkan tampil di atas aplikasi lain dulu"); isChecked = false }
                    else startForegroundService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
                }
            }
        }
        val bb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val bt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bt.addView(label("Bubble melayang", 14f, Color.WHITE, true))
        bt.addView(label("Tombol V di atas layar: ON/OFF mapping dan edit tombol.", 12f, MUTED, false))
        bb.addView(bt, LinearLayout.LayoutParams(0, W, 1f)); bb.addView(sw)
        col.addView(bb, gap())

        col.addView(label("Tips: F8 di keyboard mematikan mapping. Untuk menyalakan lagi, tap bubble V.", 12f, MUTED, false)
            .apply { setPadding(0, dp(10), 0, 0) })
        return ScrollView(this).apply { addView(col) }
    }
}
