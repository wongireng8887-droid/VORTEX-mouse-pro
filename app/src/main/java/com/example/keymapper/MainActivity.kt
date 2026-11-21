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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(topBar())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(M, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)
        select(0)
        ShizukuBridge.init()
        ShizukuBridge.onChange = { runOnUiThread { if (tab == 2) select(2) else updateStatus() } }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        ShizukuBridge.tryBind()
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
        textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun setChip(tv: TextView, text: String, ok: Boolean) {
        tv.text = text
        tv.setIcon(if (ok) R.drawable.ic_dot else R.drawable.ic_ring, if (ok) MINT else MUTED, 10)
        tv.setTextColor(if (ok) Color.WHITE else MUTED)
        tv.background = rounded(if (ok) GREEN else CARD, 20)
    }

    private fun updateStatus() {
        val mouse = hasMouse()
        val kb = hasKeyboard()
        setChip(chipMouse, if (mouse) "Mouse tersambung" else "Mouse tidak tersambung", mouse)
        setChip(chipKb, if (kb) "Keyboard tersambung" else "Keyboard tidak tersambung", kb)
        val z = ShizukuBridge.state(this) == 4
        setChip(chipSvc, if (z) "Shizuku tersambung" else "Shizuku belum siap", z)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(6))
        }
        val logo = TextView(this).apply {
            text = "V"; textSize = 22f; setTextColor(MINT); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFF12201B.toInt()); setStroke(dp(3), MINT)
            }
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(label("Vortex Mouse Pro", 18f, Color.WHITE, true))
        titles.addView(label("Keyboard & mouse untuk game Android", 11f, MUTED, false))
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
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { select(i) }
                addView(ImageView(context).apply { setImageResource(navIcons[i]) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(context).apply {
                    text = n; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), 0, 0, 0)
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

    // ---------- kesiapan ----------
    private fun ready(): Boolean = Settings.canDrawOverlays(this) && ShizukuBridge.state(this) == 4

    // ---------- tab: game ----------
    private fun gamesView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill("Tambah Game", CARD) { addGame() }.also { it.setIcon(R.drawable.ic_add, MINT, 16) },
            LinearLayout.LayoutParams(W, W).apply { setMargins(dp(24), dp(6), 0, dp(6)) })
        if (!ready()) {
            top.addView(pill("Belum siap main - buka Persiapan", 0xFF7A4B06.toInt()) { select(2) }
                .also { it.setIcon(R.drawable.ic_warning, AMBER, 16) },
                LinearLayout.LayoutParams(W, W).apply { setMargins(dp(12), dp(6), 0, dp(6)) })
        }
        col.addView(top)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), dp(10))
        }
        val games = Store.games(this)
        if (games.isEmpty()) {
            row.addView(label("Belum ada game.\nTap \"Tambah Game\" untuk memilih game dari aplikasi yang terpasang.", 14f, MUTED, false))
        }
        games.forEach { pkg ->
            gameCard(pkg)?.let {
                row.addView(it, LinearLayout.LayoutParams(dp(190), M).apply { rightMargin = dp(16) })
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
        card.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(14) })
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

    private fun launchGame(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan 'Tampilkan di atas aplikasi lain' dulu"); select(2); return
        }
        if (ShizukuBridge.state(this) != 4) {
            toast("Sambungkan Shizuku dulu (debug nirkabel). Ikuti langkah di Persiapan."); select(2); return
        }
        val err = ShizukuBridge.selfTest()
        if (err != null) {
            toast("Tes sentuhan gagal: $err"); select(2); return
        }
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Game tidak bisa dibuka"); return }
        startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        startActivity(i)
    }

    // ---------- tab: pemetaan ----------
    private fun fn(kind: String) = when (kind) {
        "TAP" -> "Tombol"; "UP" -> "Joystick atas"; "DOWN" -> "Joystick bawah"
        "LEFT" -> "Joystick kiri"; "LOOK" -> "FPS Look"; else -> "Joystick kanan"
    }

    private fun mapView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
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

    // ---------- tab: pengaturan / persiapan ----------
    private fun settingRow(title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 15f, Color.WHITE, true).also {
            it.setIcon(if (ok) R.drawable.ic_check else R.drawable.ic_warning, if (ok) MINT else AMBER, 18)
        })
        t.addView(label(desc, 12f, MUTED, false))
        r.addView(t, LinearLayout.LayoutParams(0, W, 1f))
        r.addView(pill(action, GREEN, onClick))
        return r
    }

    private fun onShizukuAction(s: Int) {
        when (s) {
            0 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            1 -> {
                val i = packageManager.getLaunchIntentForPackage(ShizukuBridge.PKG)
                if (i != null) startActivity(i) else toast("Buka aplikasi Shizuku dari layar utama")
            }
            2 -> ShizukuBridge.requestPermission()
            else -> { ShizukuBridge.tryBind(); select(2) }
        }
    }

    private fun runTouchTest() {
        val err = ShizukuBridge.selfTest()
        toast(if (err == null) "Tes sentuhan: OK" else "Tes sentuhan gagal: $err")
        select(2)
    }

    private fun settingsView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val lp = { LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) } }
        val zs = ShizukuBridge.state(this)

        col.addView(label("Persiapan sebelum main", 18f, Color.WHITE, true))
        col.addView(label("Semua langkah harus centang sebelum Mulai Game bisa dipakai.", 12f, MUTED, false))

        col.addView(settingRow("1. Tampilkan di atas aplikasi lain",
            "Untuk bubble, tombol di layar, dan menangkap keyboard/mouse.",
            Settings.canDrawOverlays(this), "Buka") { openOverlaySettings() }, lp())

        val s2 = when (zs) {
            0 -> "Shizuku belum terpasang. Unduh dulu."
            1 -> "Shizuku belum jalan. Aktifkan Debugging nirkabel, buka Shizuku, lalu Pairing dan Mulai."
            else -> "Shizuku berjalan."
        }
        col.addView(settingRow("2. Shizuku berjalan (debug nirkabel)", s2, zs >= 2,
            when (zs) { 0 -> "Unduh"; 1 -> "Buka"; else -> "Segarkan" }) { onShizukuAction(if (zs >= 2) 3 else zs) }, lp())

        col.addView(settingRow("3. Izin Shizuku untuk aplikasi ini",
            "Izinkan Vortex Mouse Pro memakai Shizuku.", zs >= 3, "Izinkan") { onShizukuAction(2) }, lp())

        col.addView(settingRow("4. Layanan sentuhan tersambung",
            if (zs == 4) "Tersambung. Sentuhan disuntik multi-touch." else "Menunggu sambungan ke Shizuku.",
            zs == 4, "Segarkan") { onShizukuAction(3) }, lp())

        col.addView(settingRow("5. Tes sentuhan",
            "Memastikan injeksi sentuhan benar-benar jalan.",
            ShizukuBridge.lastTestOk, "Tes") { runTouchTest() }, lp())

        col.addView(label("\nAlat", 18f, Color.WHITE, true))
        col.addView(settingRow("Debugging nirkabel",
            "Buka pengaturannya, nyalakan, lalu pairing lewat aplikasi Shizuku.",
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1, "Buka") { openWirelessDebug() }, lp())
        col.addView(settingRow("Tes keyboard dan mouse",
            "Cek apakah HP menerima tombol keyboard, gerak mouse, dan klik mouse.",
            hasKeyboard() || hasMouse(), "Buka") { startActivity(Intent(this, TestActivity::class.java)) }, lp())

        val sw = Switch(this).apply {
            isChecked = OverlayService.instance?.isBubbleShown() == true
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (!ready()) { toast("Selesaikan persiapan dulu"); isChecked = false }
                    else startForegroundService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
                }
            }
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label("Bubble melayang", 15f, Color.WHITE, true))
        t.addView(label("Tombol V di atas layar: ON/OFF mapping, edit tombol, tes sentuhan.", 12f, MUTED, false))
        r.addView(t, LinearLayout.LayoutParams(0, W, 1f)); r.addView(sw)
        col.addView(r, lp())

        col.addView(label("\nTips: tekan F8 di keyboard untuk mematikan mapping. Untuk menyalakan lagi, tap bubble V di layar.",
            12f, MUTED, false))
        return ScrollView(this).apply { addView(col) }
    }
}
