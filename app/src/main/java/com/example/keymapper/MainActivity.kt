package com.example.keymapper

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
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
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher

class MainActivity : Activity() {
    private val BG = 0xFF020D0B.toInt()
    private val CARD = 0xFF09231D.toInt()
    private val GREEN = 0xFF00A879.toInt()
    private val MINT = 0xFF00E6B3.toInt()
    private val MUTED = 0xFFA8BCB6.toInt()
    private val NAV = 0xFF031713.toInt()
    private val AMBER = 0xFFF59E0B.toInt()
    private val navIcons = listOf(R.drawable.ic_game, R.drawable.ic_power, R.drawable.ic_settings)
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private lateinit var chipMouse: TextView
    private lateinit var chipKb: TextView
    private lateinit var chipSvc: TextView
    private var tab = 0
    private var showingAddGame = false

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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingAddGame) {
            showingAddGame = false
            select(0)
        } else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        Injector.autoStart(this, null)
        if (Store.prefs(this).getBoolean("overlay_enabled", true) && OverlayService.instance == null && Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        }
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
        // Status koneksi ditampilkan langsung pada header; select() membangun ulang tampilan.
    }


    private fun neonBg(color: Int = CARD, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0xFF087D65.toInt())
        }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF04231C.toInt(), 0xFF03110E.toInt(), 0xFF020D0B.toInt())
            )
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vortex_app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        title.addView(label("Vortex Mouse ", 23f, Color.WHITE, true))
        title.addView(label("Pro", 23f, MINT, true))
        titles.addView(title)
        titles.addView(label("Gaming Controller", 14f, MUTED, false))
        bar.addView(titles, LinearLayout.LayoutParams(0, W, 1f))

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = neonBg(0xFF041A15.toInt(), 28)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        status.addView(View(this).apply { background = rounded(MINT, 20) },
            LinearLayout.LayoutParams(dp(10), dp(10)))
        status.addView(label(if (Injector.ready) "Terhubung" else "Belum terhubung", 14f, MINT, true).apply {
            setPadding(dp(10), 0, dp(12), 0)
        })
        status.addView(label("│", 22f, MUTED, false))
        status.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mouse); setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(12) })
        status.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_keyboard); setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(18) })
        status.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings); setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(18) })
        bar.addView(status)
        return bar
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = neonBg(0xFF03130F.toInt(), 24)
            setPadding(0, dp(5), 0, dp(5))
        }
        val names = listOf("Game", "Optimisasi Performa", "Pengaturan")
        navItems = names.mapIndexed { i, n ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { select(i) }
                addView(ImageView(context).apply {
                    setImageResource(navIcons[i])
                    setColorFilter(if (i == tab) MINT else 0xFFD0DAD6.toInt())
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(context).apply {
                    text = n
                    textSize = if (i == 1) 13f else 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (i == tab) MINT else 0xFFD0DAD6.toInt())
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
        }
        navItems.forEach { nav.addView(it, LinearLayout.LayoutParams(0, W, 1f)) }
        return nav
    }

    private fun select(i: Int) {
        tab = i
        navItems.forEachIndexed { j, t ->
            val active = j == i
            t.background = if (active) neonBg(0xFF06392D.toInt(), 18) else rounded(Color.TRANSPARENT, 18)
            (t.getChildAt(0) as ImageView).setColorFilter(if (active) MINT else 0xFFD0DAD6.toInt())
            (t.getChildAt(1) as TextView).setTextColor(if (active) MINT else 0xFFD0DAD6.toInt())
        }
        content.removeAllViews()
        val v = when (i) { 0 -> gamesView(); 1 -> performanceView(); else -> settingsView() }
        content.addView(v, FrameLayout.LayoutParams(M, M))
        updateStatus()
    }

    // ---------- tab: game ----------

    private fun sectionTitle(iconText: String, titleText: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(iconText, 27f, MINT, true), LinearLayout.LayoutParams(dp(42), W))
        row.addView(label(titleText, 22f, Color.WHITE, true))
        row.addView(View(this).apply { setBackgroundColor(0xFF164B3E.toInt()) },
            LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(14) })
        return row
    }

    private fun gamesView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(12))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(sectionTitle("🎮", "Game Saya"), LinearLayout.LayoutParams(0, W, 1f))
        heading.addView(pill("+ Tambah Game", 0xFF075C48.toInt()) { addGame() })
        col.addView(heading, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(12) })

        val games = Store.games(this)
        if (games.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = neonBg(0xFF071D17.toInt(), 22)
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }
            empty.addView(label("Belum ada game", 19f, Color.WHITE, true))
            empty.addView(label("Tambahkan game yang sudah terpasang untuk mulai.", 13f, MUTED, false).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14))
            })
            empty.addView(pill("Tambah Game", GREEN) { addGame() })
            col.addView(empty, LinearLayout.LayoutParams(M, 0, 1f))
        } else {
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            games.forEach { pkg ->
                val pm = packageManager
                val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }
                if (info != null) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = neonBg(0xFF071D18.toInt(), 22)
                        setPadding(dp(18), dp(12), dp(22), dp(12))
                        setOnClickListener { launchGame(pkg) }
                    }
                    card.addView(ImageView(this).apply {
                        setImageDrawable(pm.getApplicationIcon(info))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(82), dp(82)))
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(22), 0, 0, 0)
                    }
                    details.addView(label(pm.getApplicationLabel(info).toString(), 21f, Color.WHITE, true))
                    details.addView(label("✓  Konfigurasi tersedia", 14f, MUTED, true).apply { setPadding(0, dp(5), 0, 0) })
                    card.addView(details, LinearLayout.LayoutParams(0, W, 1f))
                    card.addView(label("›", 42f, MINT, false))
                    list.addView(card, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(12) })
                    val start = pill("▶   Mulai Game", 0xFF008D68.toInt()) { launchGame(pkg) }.apply {
                        textSize = 20f
                        setPadding(dp(20), dp(14), dp(20), dp(14))
                    }
                    list.addView(start, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(16) })
                }
            }
            scroll.addView(list)
            col.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))
        }
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
        showingAddGame = true
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101615.toInt())
        }

        // Header: back arrow + title, matching the supplied reference screen.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(20), 0)
            setBackgroundColor(0xFF1D2524.toInt())
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 42f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(16), dp(5))
            setOnClickListener { showingAddGame = false; select(0) }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(64)))
        header.addView(label("Tambah Game", 21f, Color.WHITE, true))
        page.addView(header, LinearLayout.LayoutParams(M, dp(64)))
        page.addView(View(this).apply { setBackgroundColor(0xFF202927.toInt()) }, LinearLayout.LayoutParams(M, dp(1)))

        // Search bar.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundColor(0xFF101615.toInt())
        }
        searchRow.addView(label("⌕", 30f, Color.WHITE, false), LinearLayout.LayoutParams(dp(42), W))
        val search = EditText(this).apply {
            hint = "Tambahkan game dan nikmati bermain dengan keyboard dan mouse"
            setHintTextColor(0xFF77817E.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            background = null
            setPadding(dp(8), dp(16), 0, dp(16))
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, W, 1f))
        page.addView(searchRow, LinearLayout.LayoutParams(M, dp(76)))
        page.addView(View(this).apply { setBackgroundColor(0xFF202927.toInt()) }, LinearLayout.LayoutParams(M, dp(1)))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        page.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))

        fun render(filter: String = "") {
            list.removeAllViews()
            val selected = Store.games(this)
            val visible = apps.filter { it.loadLabel(pm).toString().contains(filter, ignoreCase = true) }
            if (visible.isEmpty()) {
                list.addView(label("Aplikasi tidak ditemukan", 15f, MUTED, false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(32), dp(20), dp(32))
                }, LinearLayout.LayoutParams(M, W))
            }
            visible.forEach { app ->
                val pkg = app.activityInfo.packageName
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(28), dp(12))
                    minimumHeight = dp(94)
                }
                row.addView(ImageView(this).apply {
                    setImageDrawable(app.loadIcon(pm))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(54), dp(54)))
                row.addView(label(app.loadLabel(pm).toString(), 18f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(22), 0, dp(12), 0)
                }, LinearLayout.LayoutParams(0, W, 1f))
                val toggle = Switch(this).apply {
                    isChecked = pkg in selected
                    showText = false
                    thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Color.WHITE, 0xFF929997.toInt())
                    )
                    trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(0xFF20DFA8.toInt(), 0xFF303937.toInt())
                    )
                    setOnCheckedChangeListener { _, checked ->
                        val now = Store.games(this@MainActivity).toMutableSet()
                        if (checked) now.add(pkg) else now.remove(pkg)
                        Store.saveGames(this@MainActivity, now.toList())
                    }
                }
                row.addView(toggle, LinearLayout.LayoutParams(dp(64), dp(48)))
                list.addView(row, LinearLayout.LayoutParams(M, W))
            }
        }
        render()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        content.removeAllViews()
        content.addView(page, FrameLayout.LayoutParams(M, M))
    }

    private fun startGame(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Game tidak bisa dibuka"); return }
        Store.setCurrentGame(this, pkg)
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

    // ---------- profil keymap per game ----------
    private fun profileBar(col: LinearLayout) {
        val game = Store.currentGame(this)
        if (game.isEmpty()) {
            col.addView(label("Mulai game dari tab Game dulu, setelah itu profil keymap dibuat per game.", 12f, AMBER, false),
                LinearLayout.LayoutParams(W, W).apply { topMargin = dp(8) })
            return
        }
        val gname = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(game, 0)).toString()
        } catch (e: Exception) { game }
        val names = Store.profiles(this, game)
        val active = Store.activeProfile(this, game)
        col.addView(label("Game: $gname", 14f, MINT, true), LinearLayout.LayoutParams(W, W).apply { topMargin = dp(10) })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        names.forEach { n ->
            row.addView(pill(n, if (n == active) GREEN else CARD) {
                Store.setActiveProfile(this, game, n); select(1)
            }, LinearLayout.LayoutParams(W, W).apply { rightMargin = dp(8) })
        }
        row.addView(pill("+ Profil", CARD) {
            askProfileName("Profil baru (disalin dari profil aktif)", "") { n ->
                if (Store.createProfile(this, game, n)) select(1) else toast("Nama kosong, terlalu panjang, atau sudah dipakai")
            }
        }, LinearLayout.LayoutParams(W, W))
        scroll.addView(row)
        col.addView(scroll, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) })
        val act = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        act.addView(pill("Ganti nama", CARD) {
            askProfileName("Ganti nama profil", active) { n ->
                if (Store.renameProfile(this, game, active, n)) select(1) else toast("Nama kosong, terlalu panjang, atau sudah dipakai")
            }
        }, LinearLayout.LayoutParams(W, W).apply { rightMargin = dp(8) })
        act.addView(pill("Hapus profil", 0xFF5B2A2A.toInt()) {
            if (names.size <= 1) {
                toast("Minimal harus ada satu profil")
            } else {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Hapus profil \"$active\"?")
                    .setPositiveButton("Hapus") { _, _ -> Store.deleteProfile(this, game, active); select(1) }
                    .setNegativeButton("Batal", null).show()
            }
        }, LinearLayout.LayoutParams(W, W))
        col.addView(act, LinearLayout.LayoutParams(W, W).apply { topMargin = dp(8) })
    }

    private fun askProfileName(title: String, initial: String, onOk: (String) -> Unit) {
        val et = android.widget.EditText(this)
        et.setText(initial)
        et.setSingleLine(true)
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(MUTED)
        et.setSelection(et.text.length)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setView(et)
            .setPositiveButton("Simpan") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Batal", null).show()
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
        profileBar(col)
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

    @Suppress("DEPRECATION")
    private fun usageAccessOk(): Boolean = try {
        val ao = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }


    private fun infoValue(title: String, value: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 13f, MUTED, false), LinearLayout.LayoutParams(0, W, 1f))
        row.addView(label(value, 14f, Color.WHITE, true))
        return row
    }

    private fun deviceInfoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonBg(0xFF061B15.toInt(), 22)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(label("▣  Info Perangkat", 19f, Color.WHITE, true))
        card.addView(View(this).apply { setBackgroundColor(0xFF087D65.toInt()) },
            LinearLayout.LayoutParams(M, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Tidak diketahui"
        val bits = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64-bit" else "32-bit"
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ram = String.format("%.1f GB", mi.totalMem / 1073741824.0)
        val stat = StatFs(filesDir.absolutePath)
        val storage = String.format("%.1f GB", stat.totalBytes / 1073741824.0)
        listOf(
            infoValue("Model", model),
            infoValue("Android", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()),
            infoValue("Arsitektur", "$abi ($bits)"),
            infoValue("RAM", ram),
            infoValue("Penyimpanan", storage)
        ).forEachIndexed { i, v ->
            card.addView(v, LinearLayout.LayoutParams(M, W).apply { topMargin = if (i == 0) dp(2) else dp(13) })
            if (i == 2) card.addView(View(this).apply { setBackgroundColor(0xFF087D65.toInt()) },
                LinearLayout.LayoutParams(M, dp(1)).apply { topMargin = dp(12) })
        }
        return card
    }

    private fun preferenceSwitch(key: String, title: String, desc: String, iconText: String,
                                 defaultValue: Boolean, explain: String? = null): View {
        val prefs = getSharedPreferences("performance_ui", Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = neonBg(0xFF08231B.toInt(), 20)
            setPadding(dp(14), dp(10), dp(16), dp(10))
        }
        val ico = label(iconText, 28f, MINT, true).apply { gravity = Gravity.CENTER }
        row.addView(ico, LinearLayout.LayoutParams(dp(72), dp(64)))
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        textCol.addView(label(title, 17f, Color.WHITE, true))
        textCol.addView(label(desc, 12f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(textCol, LinearLayout.LayoutParams(0, W, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFFB9C5C0.toInt()))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF00DDA5.toInt(), 0xFF4A5752.toInt()))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (explain != null) toast(explain)
            }
        }
        row.addView(sw)
        return row
    }

    private fun performanceView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Optimisasi Performa", 21f, Color.WHITE, true))
        heading.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(MINT, 0x0000E6B3)
            ).apply {
                cornerRadius = dp(4).toFloat()
            }
        }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { leftMargin = dp(16) })
        left.addView(heading, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(10) })
        left.addView(preferenceSwitch("perf_mode", "Mode Performa",
            "Kurangi animasi antarmuka aplikasi saat bermain.", "ϟ", true),
            LinearLayout.LayoutParams(M, 0, 1f).apply { bottomMargin = dp(7) })
        left.addView(preferenceSwitch("cpu_hint", "Optimasi CPU",
            "Preferensi aplikasi; Android tetap mengatur CPU sistem.", "▦", true,
            "Android tidak memberi aplikasi biasa kendali langsung atas frekuensi CPU."),
            LinearLayout.LayoutParams(M, 0, 1f).apply { bottomMargin = dp(7) })
        left.addView(preferenceSwitch("ram_hint", "Optimasi RAM",
            "Kelola aktivitas aplikasi; bukan pembersih RAM paksa.", "▤", true,
            "RAM dikelola otomatis oleh Android. Vortex tidak mematikan aplikasi lain."),
            LinearLayout.LayoutParams(M, 0, 1f).apply { bottomMargin = dp(7) })
        left.addView(preferenceSwitch("power_save", "Hemat Daya",
            "Simpan preferensi untuk mengurangi aktivitas aplikasi.", "▣", false),
            LinearLayout.LayoutParams(M, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(0, M, 2f).apply { rightMargin = dp(10) })
        root.addView(deviceInfoCard(), LinearLayout.LayoutParams(0, M, 1f).apply { leftMargin = dp(10) })
        return root
    }

    private fun settingRow(iconText: String, title: String, desc: String, status: String,
                           action: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = neonBg(0xFF08231B.toInt(), 18)
            setPadding(dp(12), dp(9), dp(16), dp(9))
        }
        row.addView(label(iconText, 27f, MINT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(64), dp(60)))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        details.addView(label(title, 17f, Color.WHITE, true))
        details.addView(label(desc, 12f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(details, LinearLayout.LayoutParams(0, W, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        right.addView(label(status, 12f, MINT, true).apply {
            background = rounded(0xFF075D48.toInt(), 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        right.addView(label("›", 32f, MINT, false).apply { setPadding(dp(10), 0, 0, 0) })
        row.addView(right)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun settingsView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(10))
        }
        val connected = Injector.ready
        val banner = label(
            if (connected) "✓  Terhubung lewat ${Injector.mode}. Siap main."
            else "⚠  Belum terhubung. Pilih metode koneksi.",
            15f, Color.WHITE, true
        ).apply {
            background = rounded(if (connected) 0xFF087451.toInt() else 0xFF684307.toInt(), 18)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        col.addView(banner, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(10) })
        col.addView(label("Koneksi & Izin", 22f, Color.WHITE, true).apply {
            setPadding(dp(2), dp(4), 0, dp(8))
        })
        val shizukuState = ShizukuBridge.state(this)
        val shizukuStatus = when {
            Injector.ready && Injector.mode == "Shizuku" -> "Aktif"
            shizukuState == 0 -> "Belum ada"
            shizukuState == 1 -> "Tidak berjalan"
            shizukuState == 2 -> "Perlu izin"
            else -> "Siap"
        }
        col.addView(settingRow("⬡", "Shizuku", "Hubungkan layanan melalui Shizuku.",
            shizukuStatus, "Kelola") {
            when (shizukuState) {
                0 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                1 -> packageManager.getLaunchIntentForPackage(ShizukuBridge.PKG)?.let { startActivity(it) }
                2 -> ShizukuBridge.requestPermission()
                else -> {
                    toast("Menyambung...")
                    Injector.connect(this, "Shizuku") { connectDone(it) }
                }
            }
        }, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(8) })
        val adbStatus = when {
            Injector.ready && Injector.mode == "ADB" -> "Terhubung"
            AdbBackend.paired(this) -> "Dipair"
            else -> "Belum dipair"
        }
        col.addView(settingRow("⌘", "Pairing ADB", "Hubungkan perangkat melalui ADB nirkabel (opsional).",
            adbStatus, "Pairing") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pairing ADB")
                .setMessage("Aktifkan Debugging nirkabel di Opsi Pengembang, lalu mulai pairing.")
                .setPositiveButton("Mulai Pairing") { _, _ -> PairHelper.start(this); openWirelessDebug() }
                .setNeutralButton("Sambungkan") { _, _ ->
                    toast("Menyambung...")
                    Injector.connect(this, "ADB") { connectDone(it) }
                }
                .setNegativeButton("Batal", null).show()
        }, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(8) })
        val overlayOk = Settings.canDrawOverlays(this)
        col.addView(settingRow("▰", "Overlay", "Izin tampil di atas game untuk bubble dan kontrol.",
            if (overlayOk) "Diizinkan" else "Belum", if (overlayOk) "Aktif" else "Izinkan") {
            openOverlaySettings()
        }, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(8) })
        val notifPrefs = getSharedPreferences("vortex_settings", Context.MODE_PRIVATE)
        val notifRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = neonBg(0xFF08231B.toInt(), 18)
            setPadding(dp(12), dp(9), dp(16), dp(9))
        }
        notifRow.addView(label("♟", 27f, MINT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(64), dp(60)))
        val notifText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        notifText.addView(label("Notifikasi", 17f, Color.WHITE, true))
        notifText.addView(label("Aktifkan notifikasi untuk pembaruan dan info penting.", 12f, MUTED, false))
        notifRow.addView(notifText, LinearLayout.LayoutParams(0, W, 1f))
        val notif = Switch(this).apply {
            isChecked = notifPrefs.getBoolean("notifications_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                notifPrefs.edit().putBoolean("notifications_enabled", checked).apply()
                if (Build.VERSION.SDK_INT >= 33 && checked &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
                }
            }
        }
        notifRow.addView(notif)
        col.addView(notifRow, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dp(8) })
        col.addView(label("Izin dan status perangkat diperiksa langsung dari Android.", 12f, MUTED, false)
            .apply { setPadding(dp(4), dp(5), 0, 0) })
        return ScrollView(this).apply { addView(col) }
    }

}
