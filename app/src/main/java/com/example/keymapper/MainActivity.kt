package com.example.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.Uri
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
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<TextView>
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
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        select(tab)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
    }

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

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

    // ---------- top bar & nav ----------
    private fun chip() = TextView(this).apply {
        textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun setChip(tv: TextView, text: String, ok: Boolean) {
        tv.text = (if (ok) "● " else "○ ") + text
        tv.setTextColor(if (ok) Color.WHITE else MUTED)
        tv.background = rounded(if (ok) GREEN else CARD, 20)
    }

    private fun updateStatus() {
        setChip(chipMouse, if (hasMouse()) "Mouse tersambung" else "Mouse tidak tersambung", hasMouse())
        setChip(chipKb, if (hasKeyboard()) "Keyboard tersambung" else "Keyboard tidak tersambung", hasKeyboard())
        val on = KeyMapService.instance != null
        setChip(chipSvc, if (on) "Service aktif" else "Service mati", on)
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
        val names = listOf("🎮  Mulai Game", "⌨  Pemetaan", "⚙  Pengaturan")
        navItems = names.mapIndexed { i, n ->
            TextView(this).apply {
                text = n; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { select(i) }
            }
        }
        navItems.forEach { nav.addView(it, LinearLayout.LayoutParams(0, W, 1f)) }
        return nav
    }

    private fun select(i: Int) {
        tab = i
        navItems.forEachIndexed { j, t ->
            t.setTextColor(if (j == i) Color.WHITE else MUTED)
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
        col.addView(pill("＋  Tambah Game", CARD) { addGame() },
            LinearLayout.LayoutParams(W, W).apply { setMargins(dp(24), dp(6), 0, dp(6)) })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), dp(10))
        }
        val games = Store.games(this)
        if (games.isEmpty()) {
            row.addView(label("Belum ada game.\nTap “Tambah Game” untuk memilih game dari aplikasi yang terpasang.", 14f, MUTED, false))
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
            toast("Izinkan 'Tampilkan di atas aplikasi lain' dulu"); openOverlaySettings(); return
        }
        val svc = KeyMapService.instance
        if (svc == null) {
            toast("Aktifkan service Aksesibilitas dulu")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        svc.showBubble()
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else toast("Game tidak bisa dibuka")
    }

    // ---------- tab: pemetaan ----------
    private fun fn(kind: String) = when (kind) {
        "TAP" -> "Tap / tahan"; "UP" -> "Joystick ↑"; "DOWN" -> "Joystick ↓"
        "LEFT" -> "Joystick ←"; else -> "Joystick →"
    }

    private fun mapView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        col.addView(label("Pemetaan tombol", 18f, Color.WHITE, true))
        col.addView(label("Edit langsung di atas game: tap bubble V → Edit tombol.", 12f, MUTED, false))
        val maps = Store.load(this)
        if (maps.isEmpty()) col.addView(label("\nBelum ada pemetaan.", 14f, MUTED, false))
        maps.forEach { m ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 14); setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            r.addView(label("${keyName(m.keyCode)}  →  ${fn(m.kind)}  (${m.x.toInt()}, ${m.y.toInt()})", 14f, Color.WHITE, false),
                LinearLayout.LayoutParams(0, W, 1f))
            r.addView(pill("Hapus", 0xFF5B2A2A.toInt()) {
                Store.save(this, Store.load(this).filter { it.keyCode != m.keyCode }); select(1)
            })
            col.addView(r, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) })
        }
        return ScrollView(this).apply { addView(col) }
    }

    // ---------- tab: pengaturan ----------
    private fun settingRow(title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label((if (ok) "✅  " else "⚠️  ") + title, 15f, Color.WHITE, true))
        t.addView(label(desc, 12f, MUTED, false))
        r.addView(t, LinearLayout.LayoutParams(0, W, 1f))
        r.addView(pill(action, GREEN, onClick))
        return r
    }

    private fun settingsView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val lp = { LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) } }
        col.addView(label("Pengaturan", 18f, Color.WHITE, true))
        col.addView(settingRow("Aksesibilitas",
            "Wajib: membaca tombol keyboard & menyentuh layar game.",
            KeyMapService.instance != null, "Buka") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp())
        col.addView(settingRow("Tampilkan di atas aplikasi lain",
            "Untuk bubble melayang & editor tombol di atas game.",
            Settings.canDrawOverlays(this), "Buka") { openOverlaySettings() }, lp())

        val sw = Switch(this).apply {
            isChecked = KeyMapService.instance?.isBubbleShown() == true
            setOnCheckedChangeListener { _, on ->
                val svc = KeyMapService.instance
                if (svc == null) { toast("Aktifkan service Aksesibilitas dulu"); isChecked = false }
                else if (on) svc.showBubble() else svc.hideBubble()
            }
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label("Bubble melayang", 15f, Color.WHITE, true))
        t.addView(label("Tombol V di atas layar: ON/OFF mapping & edit tombol.", 12f, MUTED, false))
        r.addView(t, LinearLayout.LayoutParams(0, W, 1f)); r.addView(sw)
        col.addView(r, lp())

        col.addView(label("\nTips: tekan F8 di keyboard untuk ON/OFF mapping.\n" +
            "Kalau tombol Aksesibilitas abu-abu: Pengaturan → Aplikasi → Vortex Mouse Pro → ⋮ → Izinkan pengaturan terbatas.",
            12f, MUTED, false))
        return ScrollView(this).apply { addView(col) }
    }
}
