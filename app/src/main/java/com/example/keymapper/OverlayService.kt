package com.example.keymapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MINT = 0xFF34D399.toInt()

class OverlayService : Service() {
    companion object {
        var instance: OverlayService? = null
        const val ACTION_SHOW = "vortex.SHOW"
        const val ACTION_HIDE = "vortex.HIDE"
        private const val CHANNEL = "vortex_overlay"
    }

    private var maps: List<Mapping> = emptyList()
    var enabledMap = true
        private set
    private var editing = false
    private var hudVisible = true
    private val pressed = HashSet<Int>()
    private val pulsing = HashSet<Int>()
    private val repeaters = HashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastEvt = "-"
    private var lastWarn = 0L

    // FPS Look
    private var lookActive = false
    private var lookX = 0f
    private var lookY = 0f
    private var accX = 0f
    private var accY = 0f
    private var prevButtons = 0

    // sentuhan lewat Shizuku
    private var desired: Map<String, Pair<PointF, PointF>> = emptyMap()
    private val live = HashMap<String, Pair<Int, PointF>>()

    // overlay
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var panel: View? = null
    private var panelClosedAt = 0L
    private var editor: EditorView? = null
    private var hud: MapCanvas? = null
    private var capture: CaptureView? = null
    private var bubbleX = 40
    private var bubbleY = 260
    private var cursorLayer: FrameLayout? = null
    private var cursorImg: ImageView? = null
    private var curX = -1f
    private var curY = -1f
    private var cursorSpeed = 1.5f
    private var cursorDown = false
    private var leftByCursor = false
    private var cursorFlushPending = false
    private val cursorFlush = Runnable { cursorFlushPending = false; if (cursorDown) refresh() }
    private var bubbleSizeDp = 60
    private var bubbleOpacity = 1f

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        maps = Store.load(this)
        hud?.invalidate()
        updateCapture()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        maps = Store.load(this)
        bubbleSizeDp = Store.prefs(this).getInt("bubble_size", 60).coerceIn(36, 96)
        bubbleOpacity = Store.prefs(this).getFloat("bubble_opacity", 1f).coerceIn(0.2f, 1f)
        cursorSpeed = Store.prefs(this).getFloat("cursor_speed", 1.5f).coerceIn(0.5f, 4f)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        // If the injector process disconnects/restarts, its virtual pointers are gone.
        // Drop the local pointer cache and rebuild DOWN events after it is READY again.
        Injector.onChange = {
            handler.post {
                if (!Injector.ready) live.clear()
                else if (enabledMap && !editing) refresh()
            }
        }
        ShizukuBridge.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (intent?.action == ACTION_HIDE) {
            Store.prefs(this).edit().putBoolean("overlay_enabled", false).apply()
            shutdown()
        } else {
            Store.prefs(this).edit().putBoolean("overlay_enabled", true).apply()
            startWatch()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Layanan tetap jalan di latar supaya logo V bisa muncul otomatis saat game dibuka.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopWatch()
        hideBubble()
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        Injector.onChange = null
        instance = null
        super.onDestroy()
    }

    private fun shutdown() {
        stopWatch()
        hideBubble()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- muncul/hilang otomatis mengikuti game yang sedang dibuka ----------
    private val watchHandler = Handler(Looper.getMainLooper())
    private var watching = false
    private var fgPkg = ""
    private var fgTs = 0L
    private val watchTick = object : Runnable {
        override fun run() {
            if (!watching) return
            try { evaluateForeground() } catch (e: Exception) {}
            watchHandler.postDelayed(this, 1200)
        }
    }

    private fun startWatch() {
        watching = true
        watchHandler.removeCallbacks(watchTick)
        watchHandler.post(watchTick)
    }

    private fun stopWatch() {
        watching = false
        watchHandler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean = try {
        val ao = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun readForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (fgTs == 0L) now - 24L * 3600_000L else fgTs - 1000L
        val ev: android.app.usage.UsageEvents? = usm.queryEvents(from, now)
        if (ev == null) return
        val e = android.app.usage.UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            // 1 = ACTIVITY_RESUMED (MOVE_TO_FOREGROUND di Android lama)
            if (e.eventType == 1 && e.timeStamp >= fgTs) { fgPkg = e.packageName; fgTs = e.timeStamp }
        }
    }

    private fun evaluateForeground() {
        if (!Settings.canDrawOverlays(this)) return
        if (!hasUsageAccess()) {
            // belum bisa mendeteksi game: perilaku lama, logo tampil terus
            if (bubble == null) showForGame()
            return
        }
        readForeground()
        val inGame = fgPkg.isNotEmpty() && fgPkg in Store.games(this)
        if (inGame) {
            if (Store.currentGame(this) != fgPkg) Store.setCurrentGame(this, fgPkg)
            if (bubble == null) showForGame()
        } else if (bubble != null) {
            hideBubble()
        }
    }

    private fun showForGame() {
        if (!enabledMap) setMapping(true)
        showBubble()
        if (bubble != null && !Injector.ready) {
            Toast.makeText(this, "Belum terhubung. Buka aplikasi, pilih cara sambung.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vortex Mouse Pro", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_game)
            .setContentTitle("Vortex Mouse Pro aktif")
            .setContentText("Keyboard dan mouse dipetakan ke layar")
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_power), "Matikan",
                PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ---------- input keyboard & mouse ----------
    private fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        val ms = maps.filter { it.keyCode == code && it.kind != "LOOK" }
        val look = if (code == 0) null else maps.firstOrNull { it.kind == "LOOK" && it.trig == code }
        if (look != null) {
            lastEvt = keyName(code) + (if (down) " tekan" else " lepas")
            if (look.mode == "TAHAN") {
                if (repeat == 0) setLook(down, look)
            } else if (down && repeat == 0) {
                setLook(!lookActive, look)
            }
            return true
        }
        if (ms.isEmpty()) return false
        lastEvt = keyName(code) + (if (down) " tekan" else " lepas")
        if (down) {
            if (repeat == 0) {
                pressed.add(code)
                for (m in ms) {
                    if (m.kind != "TAP") continue
                    when (m.mode) {
                        "TAP" -> pulse(code, 1)
                        "DOUBLE" -> pulse(code, 2)
                        "REPEAT" -> startRepeat(code)
                    }
                }
            }
        } else {
            pressed.remove(code)
            for (m in ms) if (m.kind == "TAP" && m.mode == "RELEASE") pulse(code, 1)
        }
        refresh()
        return true
    }

    private fun pulse(code: Int, times: Int) {
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i >= times * 2) return
                if (i % 2 == 0) pulsing.add(code) else pulsing.remove(code)
                i++
                refresh()
                handler.postDelayed(this, 45)
            }
        }
        step.run()
    }

    private fun startRepeat(code: Int) {
        if (repeaters.containsKey(code)) return
        val r = object : Runnable {
            override fun run() {
                if (code !in pressed) {
                    pulsing.remove(code); repeaters.remove(code); refresh(); return
                }
                if (code in pulsing) pulsing.remove(code) else pulsing.add(code)
                refresh()
                handler.postDelayed(this, 45)
            }
        }
        repeaters[code] = r
        r.run()
    }

    private fun onMouseMove(dx: Float, dy: Float) {
        if (!enabledMap || editing || !lookActive) return
        lastEvt = "Mouse gerak"
        // Pointer-capture sudah mengirim delta relatif. Proses langsung agar tidak
        // menambah antrean timer di main thread yang terasa seperti input tertinggal.
        if (dx != 0f || dy != 0f) applyLook(dx, dy)
    }

    private fun applyLook(dx: Float, dy: Float) {
        val m = maps.firstOrNull { it.kind == "LOOK" } ?: return
        if (!lookActive) return
        lookX += dx * m.sens
        lookY += dy * m.sensY
        // Keep one continuous virtual touch for the whole FPS Look gesture.
        // Periodic UP/DOWN recentering interrupts the game's drag tracking.
        refresh()
    }

    private fun onMouseButtons(state: Int) {
        val changed = state xor prevButtons
        prevButtons = state
        val pairs = listOf(
            MotionEvent.BUTTON_PRIMARY to MOUSE_LEFT,
            MotionEvent.BUTTON_SECONDARY to MOUSE_RIGHT,
            MotionEvent.BUTTON_TERTIARY to MOUSE_MID)
        for ((bit, code) in pairs) {
            if ((changed and bit) == 0) continue
            val down = (state and bit) != 0
            if (code == MOUSE_LEFT && cursorClick(down)) continue
            handleKey(code, down, 0)
        }
    }

    // Klik kiri saat mode kursor (FPS Look OFF) = sentuhan di posisi kursor (bisa ditahan / geser).
    private fun cursorClick(down: Boolean): Boolean {
        if (!down) {
            if (leftByCursor) { leftByCursor = false; cursorDown = false; refresh(); return true }
            return false
        }
        if (!enabledMap || editing || lookActive) return false
        // klik kiri dipakai sebagai pemicu FPS Look -> biarkan handleKey yang mengurus
        if (maps.any { it.kind == "LOOK" && it.trig == MOUSE_LEFT }) return false
        val leftMapped = maps.any { it.kind != "LOOK" && it.keyCode == MOUSE_LEFT }
        val hasLook = maps.any { it.kind == "LOOK" }
        // tanpa marker FPS Look, klik kiri yang sudah dipetakan tetap jadi tombol biasa
        if (leftMapped && !hasLook) return false
        if (curX < 0f || curY < 0f) {
            val (sw, sh) = screenSize()
            curX = sw / 2f; curY = sh / 2f
            placeCursor()
        }
        if (cursorDown) { cursorDown = false; refresh() }   // lepaskan sentuhan kursor yang tersangkut
        leftByCursor = true
        cursorDown = true
        lastEvt = "Klik kursor " + curX.toInt() + "," + curY.toInt()
        flashCursor()
        refresh()
        return true
    }

    private fun flashCursor() {
        val img = cursorImg ?: return
        img.animate().scaleX(0.75f).scaleY(0.75f).setDuration(60).withEndAction {
            img.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        }.start()
    }

    private fun setLook(on: Boolean, m: Mapping) {
        if (on == lookActive) return
        accX = 0f; accY = 0f
        if (on) { lookActive = true; lookX = m.x; lookY = m.y } else lookActive = false
        updateCursorVisibility()
        refresh()
    }

    private fun setMapping(on: Boolean) {
        enabledMap = on
        if (!on) {
            pressed.clear(); pulsing.clear(); repeaters.clear()
            handler.removeCallbacksAndMessages(null)
            lookActive = false; accX = 0f; accY = 0f
            cursorDown = false; leftByCursor = false; cursorFlushPending = false
            refresh()
        }
        bubble?.alpha = bubbleOpacity * if (on) 1f else 0.5f
        applyHudVisibility()
        updateCapture()
        Toast.makeText(this, if (on) "Mapping ON" else "Mapping OFF (tap ikon mouse untuk membuka kontrol)", Toast.LENGTH_SHORT).show()
    }

    // ---------- injeksi sentuhan (Shizuku) ----------
    private fun isJoy(m: Mapping) = m.kind != "TAP" && m.kind != "LOOK"

    private fun refresh() {
        val t = LinkedHashMap<String, Pair<PointF, PointF>>()
        var jx = 0f; var jy = 0f; var joy: Mapping? = null
        for (m in maps) {
            when (m.kind) {
                "TAP" -> {
                    val on = if (m.mode == "HOLD") m.keyCode in pressed else m.keyCode in pulsing
                    if (on) t["k${m.keyCode}"] = PointF(m.x, m.y) to PointF(m.x, m.y)
                }
                "LOOK" -> {
                    if (lookActive) t["look"] = PointF(m.x, m.y) to PointF(lookX, lookY)
                }
                else -> {
                    if (m.keyCode in pressed) {
                        joy = m
                        when (m.kind) { "UP" -> jy -= 1f; "DOWN" -> jy += 1f; "LEFT" -> jx -= 1f; "RIGHT" -> jx += 1f }
                    }
                }
            }
        }
        joy?.let {
            val len = hypot(jx, jy)
            if (len > 0f) t["joy"] = PointF(it.x, it.y) to PointF(it.x + jx / len * it.radius, it.y + jy / len * it.radius)
        }
        if (cursorDown && curX >= 0f) t["cur"] = PointF(curX, curY) to PointF(curX, curY)
        desired = t
        hud?.invalidate()
        if (t.isNotEmpty() && !Injector.ready) {
            Injector.autoStart(this, null)
            val now = System.currentTimeMillis()
            if (now - lastWarn > 3000) {
                lastWarn = now
                Toast.makeText(this, "Belum terhubung. Sentuhan tidak jalan.", Toast.LENGTH_SHORT).show()
            }
        }
        applyShizuku()
    }

    private fun applyShizuku() {
        val gone = live.keys.filter { it !in desired }
        for (id in gone) {
            val cur = live[id] ?: continue
            Injector.touch(2, cur.first, cur.second.x, cur.second.y)
            live.remove(id)
        }
        for ((id, st) in desired) {
            val start = st.first
            val target = st.second
            val cur = live[id]
            if (cur == null) {
                val slot = (0..8).first { sl -> live.values.none { it.first == sl } }
                if (Injector.touch(0, slot, start.x, start.y)) {
                    if (target.x != start.x || target.y != start.y) Injector.touch(1, slot, target.x, target.y)
                    live[id] = slot to PointF(target.x, target.y)
                }
            } else if (cur.second.x != target.x || cur.second.y != target.y) {
                Injector.touch(1, cur.first, target.x, target.y)
                live[id] = cur.first to PointF(target.x, target.y)
            }
        }
    }

    // ---------- helper overlay ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun persist(list: List<Mapping>) {
        Store.save(this, list); maps = list
        hud?.invalidate()
        updateCapture()
    }

    private fun shortName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
        KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftR"
        KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
        KeyEvent.KEYCODE_CTRL_RIGHT -> "CtrlR"
        KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
        KeyEvent.KEYCODE_ALT_RIGHT -> "AltR"
        KeyEvent.KEYCODE_CAPS_LOCK -> "Caps"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        else -> keyName(code)
    }

    private fun setIcon(tv: TextView, res: Int, sizeDp: Int) {
        val d = getDrawable(res)!!.mutate()
        d.setTint(Color.WHITE)
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        tv.setCompoundDrawables(d, null, null, null)
        tv.compoundDrawablePadding = dp(6)
    }

    private fun chip(t: String, a: () -> Unit): TextView = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 13f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(0xFF1F2D28.toInt(), 10)
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(6); topMargin = dp(6) }
        setOnClickListener { a() }
    }

    private fun chipIc(t: String, res: Int, a: () -> Unit): TextView = chip(t, a).also { setIcon(it, res, 16) }

    private fun iconChip(res: Int, a: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = rounded(0xFF1F2D28.toInt(), 10)
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6); topMargin = dp(6) }
        setOnClickListener { a() }
    }

    private fun lbl(t: String): TextView = TextView(this).apply {
        text = t; setTextColor(0xFF9FB3AB.toInt()); textSize = 12f
        setPadding(dp(8), dp(6), dp(2), 0)
    }

    // ---------- tangkap keyboard & mouse (jendela fokus kecil) ----------
    private inner class CaptureView(c: Context) : View(c) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, e ->
                onMouseButtons(e.buttonState)
                if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    if (lookActive) onMouseMove(e.x, e.y) else moveCursorBy(e.x, e.y)
                }
                true
            }
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_F8) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) handler.post { setMapping(false) }
                return true
            }
            if (!enabledMap) return super.dispatchKeyEvent(e)
            return handleKey(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) {
                requestPointerCapture(); showCursor()
            } else {
                hideCursor()
                // Android may omit a final key/button UP when pointer capture loses
                // focus. Clear held states and release all virtual touches together.
                lookActive = false
                        prevButtons = 0
                cursorDown = false
                leftByCursor = false
                pressed.clear()
                pulsing.clear()
                repeaters.values.forEach { handler.removeCallbacks(it) }
                repeaters.clear()
                refresh()
            }
        }
    }

    private fun updateCapture() {
        val need = bubble != null && enabledMap && !editing
        if (need && capture == null) {
            val v = CaptureView(this)
            val lp = WindowManager.LayoutParams(1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            try { wm?.addView(v, lp); capture = v; v.requestFocus(); showCursor() } catch (e: Exception) {}
        } else if (!need && capture != null) {
            capture?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
            capture = null
            if (lookActive) { lookActive = false; refresh() }
            if (cursorDown) { cursorDown = false; leftByCursor = false; refresh() }
            hideCursor()
            prevButtons = 0
        }
    }

    // ---------- gambar tombol (dipakai HUD & editor) ----------
    private inner class MapCanvas(c: Context) : View(c) {
        var touch: ((MotionEvent) -> Boolean)? = null
        var selKey = -2
        var selPos: PointF? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tri = Path()

        init { paint.textAlign = Paint.Align.CENTER }

        override fun onTouchEvent(e: MotionEvent): Boolean = touch?.invoke(e) ?: false

        override fun onDraw(cv: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val groups = maps.filter { isJoy(it) }.groupBy { "${it.x},${it.y}" }
            for (g in groups.values) drawJoy(cv, g, loc)
            for (m in maps) {
                val hot = m.keyCode in pressed || m.keyCode in pulsing
                when (m.kind) {
                    "TAP" -> drawKey(cv, m.x - loc[0], m.y - loc[1], shortName(m.keyCode), m.keyCode == selKey, hot,
                        (m.radius / 150f).coerceIn(0.4f, 2f))
                    "LOOK" -> drawLook(cv, m, loc)
                }
            }
            selPos?.let { drawCross(cv, it, loc) }
        }

        private fun drawKey(cv: Canvas, x: Float, y: Float, name: String, sel: Boolean, hot: Boolean, scale: Float) {
            val radius = dp(26).toFloat() * scale
            paint.style = Paint.Style.FILL
            paint.color = if (hot) 0xE60F8F63.toInt() else 0xB3000000.toInt()
            cv.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (sel) dp(3).toFloat() else dp(1).toFloat()
            paint.color = if (sel || hot) MINT else 0xAAFFFFFF.toInt()
            cv.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = (if (name.length > 3) dp(10) else dp(15)).toFloat() * scale.coerceIn(0.75f, 1.5f)
            cv.drawText(name, x, y + paint.textSize * 0.35f, paint)
        }

        private fun drawLook(cv: Canvas, m: Mapping, loc: IntArray) {
            val cx = m.x - loc[0]
            val cy = m.y - loc[1]
            val sel = m.keyCode == selKey
            // FPS Look is represented by a clean marker only; no rotation-area ring.
            drawKey(cv, cx, cy, if (m.trig != 0) "Look " + shortName(m.trig) else "Look", sel, lookActive, 1f)
        }

        private fun drawJoy(cv: Canvas, g: List<Mapping>, loc: IntArray) {
            val c = g[0]
            val cx = c.x - loc[0]
            val cy = c.y - loc[1]
            val r = c.radius
            paint.style = Paint.Style.FILL
            paint.color = 0x66000000
            cv.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selKey == -1) dp(3).toFloat() else dp(2).toFloat()
            paint.color = if (selKey == -1) MINT else 0xAAFFFFFF.toInt()
            cv.drawCircle(cx, cy, r, paint)
            val s = dp(9).toFloat()
            for (m in g) {
                val dx = when (m.kind) { "LEFT" -> -1f; "RIGHT" -> 1f; else -> 0f }
                val dy = when (m.kind) { "UP" -> -1f; "DOWN" -> 1f; else -> 0f }
                val tx = cx + dx * r * 0.7f
                val ty = cy + dy * r * 0.7f
                tri.reset()
                tri.moveTo(tx + dx * s, ty + dy * s)
                tri.lineTo(tx - dx * s + dy * s, ty - dy * s - dx * s)
                tri.lineTo(tx - dx * s - dy * s, ty - dy * s + dx * s)
                tri.close()
                paint.style = Paint.Style.FILL
                paint.color = if (m.keyCode in pressed) MINT else 0xEEFFFFFF.toInt()
                cv.drawPath(tri, paint)
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = dp(11).toFloat()
                paint.color = 0xCCFFFFFF.toInt()
                cv.drawText(shortName(m.keyCode), cx + dx * r * 0.36f, cy + dy * r * 0.36f + dp(4), paint)
            }
        }

        private fun drawCross(cv: Canvas, p: PointF, loc: IntArray) {
            val x = p.x - loc[0]
            val y = p.y - loc[1]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0x9934D399.toInt()
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(6).toFloat()), 0f)
            cv.drawLine(0f, y, width.toFloat(), y, paint)
            cv.drawLine(x, 0f, x, height.toFloat(), paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = MINT
            paint.textSize = dp(12).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.LEFT
            cv.drawText("${p.x.toInt()}, ${p.y.toInt()}", x + dp(34), y - dp(30), paint)
            paint.textAlign = Paint.Align.CENTER
        }
    }

    // ---------- HUD tombol di atas game (tidak bisa disentuh) ----------
    private fun showHud() {
        if (hud != null) return
        val v = MapCanvas(this)
        v.alpha = 0.55f
        val lp = WindowManager.LayoutParams(MP, MP, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm?.addView(v, lp); hud = v; applyHudVisibility() } catch (e: Exception) {}
    }

    private fun hideHud() {
        hud?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        hud = null
    }

    private fun applyHudVisibility() {
        hud?.visibility = if (enabledMap && hudVisible && !editing) View.VISIBLE else View.INVISIBLE
        updateCursorVisibility()
    }

    // ---------- bubble ----------
    fun isBubbleShown(): Boolean = bubble != null

    fun showBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin 'Tampilkan di atas aplikasi lain' belum aktif", Toast.LENGTH_LONG).show()
            return
        }
        showHud()
        // Satu ikon floating saja: logo mouse PNG transparan tanpa kotak/background.
        val v = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val mouseIcon = ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.vortex_mouse_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(dp(bubbleSizeDp - 4), dp(bubbleSizeDp - 4), Gravity.CENTER)
            }
            addView(mouseIcon)
            alpha = bubbleOpacity * if (enabledMap) 1f else 0.55f
        }
        val lp = WindowManager.LayoutParams(dp(bubbleSizeDp), dp(bubbleSizeDp), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = bubbleX; y = bubbleY
        }
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        if (moved) {
                            lp.x = ox + dx; lp.y = oy + dy; bubbleX = lp.x; bubbleY = lp.y
                            wm?.updateViewLayout(view, lp); closePanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        if (editor != null) closeEditor()
                        else if (panel != null) closePanel()
                        else if (System.currentTimeMillis() - panelClosedAt > 350) openPanel()
                    }
                }
                return true
            }
        })
        try { wm?.addView(v, lp); bubble = v } catch (e: Exception) {
            Toast.makeText(this, "Gagal menampilkan bubble", Toast.LENGTH_SHORT).show()
        }
        updateCapture()
    }

    fun hideBubble() {
        closePanel(); closeEditor()
        bubble?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        bubble = null
        hideCursor()
        updateCapture()
        hideHud()
        Injector.releaseAll(); live.clear()
    }

    private fun openPanelSizeControls() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = rounded(0xF2111A17.toInt(), 14)
        }
        col.addView(chip("−") { bubbleSizeDp = (bubbleSizeDp - 8).coerceIn(36, 96); Store.prefs(this).edit().putInt("bubble_size", bubbleSizeDp).apply(); resizeBubble(); closePanel() })
        col.addView(chip("${bubbleSizeDp}dp") { closePanel(); openPanel() })
        col.addView(chip("+") { bubbleSizeDp = (bubbleSizeDp + 8).coerceIn(36, 96); Store.prefs(this).edit().putInt("bubble_size", bubbleSizeDp).apply(); resizeBubble(); closePanel() })
        val lp = WindowManager.LayoutParams(WC, WC, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = bubbleX + dp(58); y = bubbleY
        }
        try { wm?.addView(col, lp); panel = col } catch (_: Exception) {}
    }

    private fun resizeBubble() {
        val v = bubble ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.width = dp(bubbleSizeDp); lp.height = dp(bubbleSizeDp)
        ((v as? ViewGroup)?.getChildAt(0) as? ImageView)?.let { it.layoutParams = FrameLayout.LayoutParams(dp(bubbleSizeDp - 4), dp(bubbleSizeDp - 4), Gravity.CENTER) }
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm0 = resources.displayMetrics
        val w = wm ?: return dm0.widthPixels to dm0.heightPixels
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = w.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = android.util.DisplayMetrics()
                w.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Exception) {
            dm0.widthPixels to dm0.heightPixels
        }
    }

    private fun placeCursor() {
        val layer = cursorLayer ?: return
        val img = cursorImg ?: return
        val loc = IntArray(2)
        layer.getLocationOnScreen(loc)
        img.translationX = curX - loc[0]
        img.translationY = curY - loc[1]
    }

    private fun showCursor() {
        if (cursorLayer != null || bubble == null) return
        if (curX < 0f || curY < 0f) {
            val (sw, sh) = screenSize()
            curX = sw / 2f; curY = sh / 2f
        }
        val layer = FrameLayout(this)
        val img = ImageView(this)
        img.setImageResource(R.drawable.vortex_cursor)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.alpha = 0.98f
        layer.addView(img, FrameLayout.LayoutParams(dp(28), dp(28)))
        val lp = WindowManager.LayoutParams(MP, MP, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm?.addView(layer, lp)
            cursorLayer = layer
            cursorImg = img
            layer.post { placeCursor() }
            updateCursorVisibility()
        } catch (_: Exception) {}
    }

    // Mouse tertangkap (pointer capture) memberi pergeseran relatif, bukan posisi layar.
    private fun moveCursorBy(dx: Float, dy: Float) {
        if (!enabledMap || editing || lookActive) return
        val (sw, sh) = screenSize()
        if (curX < 0f || curY < 0f) { curX = sw / 2f; curY = sh / 2f }
        curX = (curX + dx * cursorSpeed).coerceIn(0f, sw - 1f)
        curY = (curY + dy * cursorSpeed).coerceIn(0f, sh - 1f)
        placeCursor()
        if (cursorDown && !cursorFlushPending) {
            cursorFlushPending = true
            handler.postDelayed(cursorFlush, 8)
        }
    }

    private fun updateCursorVisibility() {
        cursorLayer?.visibility = if (enabledMap && !editing && !lookActive) View.VISIBLE else View.INVISIBLE
    }

    private fun hideCursor() {
        cursorLayer?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        cursorLayer = null
        cursorImg = null
    }

    private fun menuItem(t: String, a: () -> Unit): TextView = chip(t, a).apply {
        layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) }
    }

    // ---------- menu samping (tap logo) ----------
    private inner class PanelRoot(c: Context) : FrameLayout(c) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); return true }
            return super.onTouchEvent(e)
        }
    }

    private inner class NebulaTile(c: Context) : LinearLayout(c) {
        var lockIcon: android.graphics.drawable.Drawable? = null
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setWillNotDraw(false)
        }

        override fun dispatchDraw(cv: Canvas) {
            super.dispatchDraw(cv)
            val w = width.toFloat()
            val h = height.toFloat()
            val s = dp(10).toFloat()
            bp.style = Paint.Style.STROKE
            bp.strokeWidth = dp(2).toFloat()
            bp.color = 0xFFC3CCC8.toInt()
            // siku di dua sudut bawah
            cv.drawLine(0f, h - 1f, s, h - 1f, bp)
            cv.drawLine(1f, h, 1f, h - s, bp)
            cv.drawLine(w, h - 1f, w - s, h - 1f, bp)
            cv.drawLine(w - 1f, h, w - 1f, h - s, bp)
            lockIcon?.let {
                it.setBounds((w - dp(30)).toInt(), dp(8), (w - dp(12)).toInt(), dp(26))
                it.draw(cv)
            }
        }
    }

    private fun openPanel() {
        val (sw, _) = screenSize()
        val pw = minOf(dp(340), (sw * 0.48f).toInt())
        val root = PanelRoot(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF5142521.toInt(), 0xF50C1210.toInt()))
        body.setPadding(dp(14), dp(16), dp(14), dp(8))

        // kepala: logo + nama
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.vortex_mouse_logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        head.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)))
        val title = TextView(this)
        title.text = "Vortex Mouse Pro"
        title.setTextColor(Color.WHITE)
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.06f
        head.addView(title, LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(10) })
        body.addView(head)

        val shz = if (Injector.ready) "OK (" + Injector.mode + ")" else "putus"
        val mouse = if (capture?.hasPointerCapture() == true) "OK" else "belum"
        val st = TextView(this)
        st.text = "Sambungan: $shz | Mouse: $mouse\nTerakhir: $lastEvt"
        st.setTextColor(0xFF8FA39B.toInt())
        st.textSize = 10f
        body.addView(st, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(6); bottomMargin = dp(6) })

        fun rebuild() { closePanel(); openPanel() }

        fun tile(icon: Int, label: String, badge: String? = null, badgeColor: Int = MINT,
                 locked: Boolean = false, onTap: () -> Unit): View {
            val t = NebulaTile(this)
            val bg = GradientDrawable()
            bg.setColor(0xFF0F1715.toInt())
            bg.setStroke(dp(1), 0xFF24312D.toInt())
            t.background = bg
            val ic = ImageView(this)
            ic.setImageResource(icon)
            ic.setColorFilter(if (locked) 0xFF8A9690.toInt() else Color.WHITE)
            t.addView(ic, LinearLayout.LayoutParams(dp(30), dp(30)))
            if (badge != null) {
                val bt = TextView(this)
                bt.text = badge
                bt.setTextColor(badgeColor)
                bt.textSize = 10f
                bt.typeface = Typeface.DEFAULT_BOLD
                bt.gravity = Gravity.CENTER
                t.addView(bt, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(2) })
            }
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(if (locked) 0xFF8A9690.toInt() else Color.WHITE)
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            tv.maxLines = 2
            t.addView(tv, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(8) })
            if (locked) {
                val d = getDrawable(R.drawable.ic_lock)?.mutate()
                d?.setTint(Color.WHITE)
                t.lockIcon = d
            }
            t.layoutParams = LinearLayout.LayoutParams(0, dp(108), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            t.isFocusable = false
            t.setOnClickListener { onTap() }
            return t
        }

        fun row2(a: View, b: View): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.addView(a)
            r.addView(b)
            return r
        }

        val game = Store.currentGame(this)
        val profBadge = if (game.isEmpty()) null else Store.activeProfile(this, game).take(10).uppercase()
        val onColor = MINT
        val offColor = 0xFFF87171.toInt()

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL

        grid.addView(row2(
            tile(R.drawable.ic_keyboard, "Edit Keymap") { closePanel(); openEditor() },
            tile(R.drawable.ic_list, "Profil Keymap", profBadge) {
                if (game.isEmpty()) {
                    Toast.makeText(this, "Mulai game dari aplikasi dulu", Toast.LENGTH_SHORT).show()
                } else {
                    val names = Store.profiles(this, game)
                    if (names.size < 2) {
                        Toast.makeText(this, "Baru ada 1 profil. Tambah di tab Pemetaan.", Toast.LENGTH_SHORT).show()
                    } else {
                        val next = names[(names.indexOf(Store.activeProfile(this, game)) + 1) % names.size]
                        Store.setActiveProfile(this, game, next)
                        Toast.makeText(this, "Profil: $next", Toast.LENGTH_SHORT).show()
                        rebuild()
                    }
                }
            }))
        grid.addView(row2(
            tile(R.drawable.ic_mouse, "Pengaturan Mouse", "x$cursorSpeed") {
                cursorSpeed = if (cursorSpeed < 1.4f) 1.5f else if (cursorSpeed < 1.9f) 2f else if (cursorSpeed < 2.9f) 3f else 1f
                Store.prefs(this).edit().putFloat("cursor_speed", cursorSpeed).apply()
                Toast.makeText(this, "Kecepatan kursor x$cursorSpeed", Toast.LENGTH_SHORT).show()
                rebuild()
            },
            tile(R.drawable.ic_settings, "Macro", null, MINT, true) {
                Toast.makeText(this, "Macro belum tersedia", Toast.LENGTH_SHORT).show()
            }))
        grid.addView(row2(
            tile(R.drawable.ic_game, "Mapping", if (enabledMap) "ON" else "OFF", if (enabledMap) onColor else offColor) {
                setMapping(!enabledMap); rebuild()
            },
            tile(R.drawable.ic_visibility, "Tombol di Layar", if (hudVisible) "ON" else "OFF", if (hudVisible) onColor else offColor) {
                hudVisible = !hudVisible; applyHudVisibility(); rebuild()
            }))
        grid.addView(row2(
            tile(R.drawable.ic_resize, "Ukuran Logo", "${bubbleSizeDp}dp") { closePanel(); openPanelSizeControls() },
            tile(R.drawable.ic_ring, "Transparansi", "${(bubbleOpacity * 100).toInt()}%") {
                bubbleOpacity = if (bubbleOpacity >= 0.99f) 0.75f else if (bubbleOpacity >= 0.7f) 0.5f else 1f
                Store.prefs(this).edit().putFloat("bubble_opacity", bubbleOpacity).apply()
                bubble?.alpha = bubbleOpacity * if (enabledMap) 1f else 0.55f
                rebuild()
            }))
        grid.addView(row2(
            tile(R.drawable.ic_exit, "Kembali ke Aplikasi") {
                closePanel()
                try {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (_: Exception) {}
            },
            tile(R.drawable.ic_power, "Matikan Overlay") {
                Store.prefs(this).edit().putBoolean("overlay_enabled", false).apply()
                shutdown()
            }))

        val scroll = android.widget.ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))
        root.addView(body, FrameLayout.LayoutParams(MP, MP))

        // gagang putih di tepi kanan: tap untuk menutup
        val r = dp(4).toFloat()
        val handle = View(this)
        val hb = GradientDrawable()
        hb.setColor(Color.WHITE)
        hb.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        handle.background = hb
        handle.setOnClickListener { closePanel() }
        root.addView(handle, FrameLayout.LayoutParams(dp(12), dp(76), Gravity.END or Gravity.TOP).apply { topMargin = dp(66) })

        val lp = WindowManager.LayoutParams(pw, MP, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm?.addView(root, lp)
            panel = root
            root.translationX = -pw.toFloat()
            root.animate().translationX(0f).setDuration(150).start()
            // kursor harus tampil di atas menu supaya bisa dipakai menekan tile
            hideCursor()
            showCursor()
        } catch (e: Exception) {}
    }

    private fun closePanel() {
        panelClosedAt = System.currentTimeMillis()
        panel?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        panel = null
    }

    // ---------- editor tombol ----------
    private fun openEditor() {
        if (editor != null) return
        editing = true
        pressed.clear(); pulsing.clear(); lookActive = false
        refresh()
        applyHudVisibility()
        updateCapture()
        val root = EditorView(this)
        val lp = WindowManager.LayoutParams(MP, MP, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm?.addView(root, lp); editor = root } catch (e: Exception) {
            editing = false; applyHudVisibility(); updateCapture()
        }
    }

    private fun closeEditor() {
        if (editor == null) return
        editor?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        editor = null
        editing = false
        if (maps.any { it.keyCode == 0 }) persist(maps.filter { it.keyCode != 0 })
        applyHudVisibility()
        updateCapture()
    }

    private inner class EditorView(c: Context) : FrameLayout(c) {
        private val NONE = -2
        private val JOY = -1
        private val canvasView = MapCanvas(c)
        private val status = TextView(c)
        private val addMenu = LinearLayout(c)
        private val selBar = LinearLayout(c)
        private val settingsBox = LinearLayout(c)
        private val settingsTitle = TextView(c)
        private val settingsBody = LinearLayout(c)
        private var sel = NONE
        private var drag = NONE
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var settingsOpen = false
        private var listening = false
        private var snap: List<Mapping> = emptyList()
        private var nudgeRep: Runnable? = null
        private val original: List<Mapping> = maps
        private val modes = listOf("HOLD", "TAP", "REPEAT", "DOUBLE", "RELEASE")
        private val modeLabels = listOf("Hold", "Tap", "Repeat Tap", "Double Tap", "Release Tap")
        private val modeHints = listOf(
            "Sentuhan ditahan selama tombol ditekan.",
            "Satu ketukan saat tombol ditekan.",
            "Mengetuk berulang selama tombol ditahan.",
            "Dua ketukan cepat saat tombol ditekan.",
            "Satu ketukan saat tombol dilepas.")
        private val hint = "Tap + untuk menambah. Tekan lingkaran lalu geser ke posisi tombol di game."

        init {
            setBackgroundColor(0x66000000)
            isFocusable = true
            isFocusableInTouchMode = true
            canvasView.touch = { e -> handleTouch(e) }
            addView(canvasView, FrameLayout.LayoutParams(MP, MP))

            val top = LinearLayout(c)
            top.orientation = LinearLayout.VERTICAL
            top.gravity = Gravity.CENTER_HORIZONTAL

            // toolbar: + (tambah)   X (batal)   V (simpan & keluar)
            val toolbar = LinearLayout(c)
            toolbar.orientation = LinearLayout.HORIZONTAL
            toolbar.gravity = Gravity.CENTER_VERTICAL
            toolbar.setPadding(dp(6), dp(6), dp(6), dp(6))
            toolbar.background = rounded(0xF2111A17.toInt(), 14)
            toolbar.isClickable = true
            toolbar.isFocusable = false
            toolbar.addView(toolBtn(R.drawable.ic_add, false) {
                addMenu.visibility = if (addMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            toolbar.addView(toolBtn(R.drawable.ic_close, false) { persist(original); closeEditor() })
            toolbar.addView(toolBtn(R.drawable.ic_check, false) { closeEditor() })
            top.addView(toolbar)

            status.setTextColor(0xFFCFE3DA.toInt())
            status.textSize = 10f
            status.text = hint
            status.gravity = Gravity.CENTER
            status.setPadding(dp(10), dp(4), dp(10), dp(4))
            status.background = rounded(0xB3000000.toInt(), 10)
            top.addView(status, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(6) })

            // menu tipe kontrol (grid 3 x 2)
            addMenu.orientation = LinearLayout.VERTICAL
            addMenu.setPadding(dp(5), dp(5), dp(5), dp(5))
            addMenu.background = rounded(0xF2111A17.toInt(), 14)
            addMenu.isClickable = true
            addMenu.isFocusable = false
            addMenu.visibility = View.GONE
            val g1 = row()
            val g2 = row()
            g1.addView(tile(R.drawable.ic_dot, "Sentuh", false) { addButton() })
            g1.addView(tile(R.drawable.ic_game, "Joystick", false) { addJoystick() })
            g1.addView(tile(R.drawable.ic_mouse, "FPS Look", false) { addLook() })
            g2.addView(tile(R.drawable.ic_keyboard, "Template", false) { addMenu.visibility = View.GONE; applyTemplate() })
            g2.addView(tile(R.drawable.ic_visibility, "Lihat Bebas", true) {})
            g2.addView(tile(R.drawable.ic_settings, "Macro", true) {})
            addMenu.addView(g1)
            addMenu.addView(g2)
            top.addView(addMenu, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(6) })

            // bar kontrol terpilih: nama, hapus, pengaturan, panah geser
            selBar.orientation = LinearLayout.HORIZONTAL
            selBar.gravity = Gravity.CENTER_VERTICAL
            selBar.setPadding(dp(6), dp(6), dp(6), dp(6))
            selBar.background = rounded(0xF2111A17.toInt(), 14)
            selBar.isClickable = true
            selBar.isFocusable = false
            selBar.visibility = View.GONE
            top.addView(selBar, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(6) })

            addView(top, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

            // panel pengaturan (muncul di sisi yang berlawanan dengan kontrol terpilih)
            settingsBox.orientation = LinearLayout.VERTICAL
            settingsBox.setPadding(dp(16), dp(14), dp(16), dp(12))
            settingsBox.background = rounded(0xF2111A17.toInt(), 14)
            settingsBox.isClickable = true
            settingsBox.isFocusable = false
            settingsBox.visibility = View.GONE
            settingsTitle.setTextColor(Color.WHITE)
            settingsTitle.textSize = 16f
            settingsTitle.typeface = Typeface.DEFAULT_BOLD
            settingsBox.addView(settingsTitle)
            val sc = android.widget.ScrollView(c)
            sc.isFocusable = false
            sc.isVerticalScrollBarEnabled = false
            settingsBody.orientation = LinearLayout.VERTICAL
            sc.addView(settingsBody)
            settingsBox.addView(sc, LinearLayout.LayoutParams(MP, 0, 1f).apply { topMargin = dp(6) })
            val foot = row()
            foot.addView(btn("BATAL", false) { persist(snap); select(NONE) },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
            foot.addView(btn("SIMPAN", true) { settingsOpen = false; listening = false; refreshPanel() },
                LinearLayout.LayoutParams(0, dp(42), 1f))
            settingsBox.addView(foot, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) })
            addView(settingsBox, FrameLayout.LayoutParams(dp(300), MP, Gravity.END))

            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    val m = selMapping()
                    if (listening || (m != null && m.kind == "TAP" && m.keyCode == 0)) assignKey(code)
                }
                true
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        override fun onDetachedFromWindow() {
            stopNudge()
            super.onDetachedFromWindow()
        }

        // ----- komponen kecil -----
        private fun toolBtn(res: Int, active: Boolean, a: () -> Unit): ImageView {
            val v = ImageView(context)
            v.setImageResource(res)
            v.setColorFilter(Color.WHITE)
            v.setPadding(dp(10), dp(8), dp(10), dp(8))
            v.background = rounded(if (active) 0xFF16A06E.toInt() else 0xFF0F6B4B.toInt(), 10)
            v.isFocusable = false
            v.layoutParams = LinearLayout.LayoutParams(dp(52), dp(40)).apply { leftMargin = dp(4); rightMargin = dp(4) }
            v.setOnClickListener { a() }
            return v
        }

        private fun arrowBtn(res: Int, dx: Float, dy: Float): ImageView {
            val v = ImageView(context)
            v.setImageResource(res)
            v.setColorFilter(Color.WHITE)
            v.setPadding(dp(6), dp(6), dp(6), dp(6))
            v.background = rounded(0xFF1F6F52.toInt(), 8)
            v.isFocusable = false
            v.layoutParams = LinearLayout.LayoutParams(dp(34), dp(30)).apply { leftMargin = dp(2); rightMargin = dp(2); topMargin = dp(2) }
            // tekan = geser sedikit, tahan = geser terus
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        nudge(dx, dy)
                        stopNudge()
                        val r = object : Runnable {
                            override fun run() {
                                nudge(dx, dy)
                                this@EditorView.postDelayed(this, 45)
                            }
                        }
                        nudgeRep = r
                        this@EditorView.postDelayed(r, 320)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopNudge()
                }
                true
            }
            return v
        }

        private fun stopNudge() {
            nudgeRep?.let { removeCallbacks(it) }
            nudgeRep = null
        }

        private fun tile(res: Int, t: String, locked: Boolean, a: () -> Unit): FrameLayout {
            val f = FrameLayout(context)
            f.background = rounded(if (locked) 0xFF1B2723.toInt() else 0xFF0F6B4B.toInt(), 12)
            f.isFocusable = false
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val ic = ImageView(context)
            ic.setImageResource(res)
            ic.setColorFilter(if (locked) 0xFF7F8D87.toInt() else Color.WHITE)
            col.addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
            val tv = TextView(context)
            tv.text = t
            tv.setTextColor(if (locked) 0xFF7F8D87.toInt() else Color.WHITE)
            tv.textSize = 11f
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(5), 0, 0)
            col.addView(tv)
            f.addView(col, FrameLayout.LayoutParams(MP, MP))
            if (locked) {
                val lk = ImageView(context)
                lk.setImageResource(R.drawable.ic_lock)
                lk.setColorFilter(0xFFB9C4C0.toInt())
                f.addView(lk, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END or Gravity.TOP).apply { topMargin = dp(5); rightMargin = dp(5) })
            }
            f.layoutParams = LinearLayout.LayoutParams(dp(86), dp(74)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            f.setOnClickListener {
                if (locked) Toast.makeText(context, "Belum tersedia", Toast.LENGTH_SHORT).show() else a()
            }
            return f
        }

        private fun btn(t: String, primary: Boolean, a: () -> Unit): TextView {
            val v = TextView(context)
            v.text = t
            v.setTextColor(Color.WHITE)
            v.textSize = 13f
            v.typeface = Typeface.DEFAULT_BOLD
            v.gravity = Gravity.CENTER
            v.setPadding(dp(14), dp(8), dp(14), dp(8))
            v.background = rounded(if (primary) 0xFF0F6B4B.toInt() else 0xFF1F2D28.toInt(), 10)
            v.isFocusable = false
            v.setOnClickListener { a() }
            return v
        }

        private fun row(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        private fun tabsRow(labels: List<String>, activeIdx: Int, onPick: (Int) -> Unit): LinearLayout {
            val r = row()
            labels.forEachIndexed { i, l ->
                val on = i == activeIdx
                val tv = TextView(context)
                tv.text = l
                tv.textSize = 11f
                tv.gravity = Gravity.CENTER
                tv.setTextColor(if (on) Color.BLACK else 0xFFB6C4BE.toInt())
                tv.setPadding(dp(4), dp(9), dp(4), dp(9))
                tv.background = rounded(if (on) MINT else 0xFF1F2D28.toInt(), 10)
                tv.isFocusable = false
                tv.setOnClickListener { onPick(i) }
                r.addView(tv, LinearLayout.LayoutParams(0, WC, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
            }
            return r
        }

        private fun stepRow(t: String, v: String, minus: () -> Unit, plus: () -> Unit): LinearLayout {
            val r = row()
            val name = TextView(context)
            name.text = t
            name.setTextColor(0xFF9FB3AB.toInt())
            name.textSize = 13f
            name.setPadding(dp(6), 0, 0, 0)
            r.addView(name, LinearLayout.LayoutParams(0, WC, 1f))
            r.addView(iconChip(R.drawable.ic_minus) { minus() })
            val value = TextView(context)
            value.text = v
            value.setTextColor(Color.WHITE)
            value.textSize = 14f
            value.typeface = Typeface.DEFAULT_BOLD
            value.gravity = Gravity.CENTER
            r.addView(value, LinearLayout.LayoutParams(dp(52), WC))
            r.addView(iconChip(R.drawable.ic_add) { plus() })
            return r
        }

        private fun selName(m: Mapping): String = when {
            isJoy(m) -> "Joystick"
            m.kind == "LOOK" -> "FPS Look"
            m.keyCode == 0 -> "Sentuh baru"
            else -> "Sentuh " + keyName(m.keyCode)
        }

        // ----- pemilihan -----
        private fun isSel(m: Mapping): Boolean = when (sel) {
            NONE -> false
            JOY -> isJoy(m)
            else -> m.keyCode == sel && !isJoy(m)
        }

        private fun selMapping(): Mapping? = maps.firstOrNull { isSel(it) }

        private fun select(s: Int) {
            if (s != sel) { settingsOpen = false; listening = false }
            sel = s
            canvasView.selKey = s
            val m = selMapping()
            canvasView.selPos = if (m == null) null else PointF(m.x, m.y)
            canvasView.invalidate()
            refreshPanel()
        }

        private fun refreshPanel() {
            stopNudge()
            selBar.removeAllViews()
            val m = selMapping()
            if (sel == NONE || m == null) {
                selBar.visibility = View.GONE
                settingsBox.visibility = View.GONE
                settingsOpen = false
                listening = false
                return
            }
            selBar.visibility = View.VISIBLE
            val name = TextView(context)
            name.text = selName(m)
            name.setTextColor(Color.WHITE)
            name.textSize = 13f
            name.typeface = Typeface.DEFAULT_BOLD
            name.gravity = Gravity.CENTER
            name.setPadding(dp(14), dp(8), dp(14), dp(8))
            name.background = rounded(0xFF0F6B4B.toInt(), 10)
            selBar.addView(name, LinearLayout.LayoutParams(WC, dp(40)).apply { rightMargin = dp(6) })
            selBar.addView(toolBtn(R.drawable.ic_trash, false) { deleteSel() })
            selBar.addView(toolBtn(R.drawable.ic_settings, settingsOpen) { toggleSettings() })

            val pad = LinearLayout(context)
            pad.orientation = LinearLayout.VERTICAL
            pad.gravity = Gravity.CENTER_HORIZONTAL
            pad.addView(arrowBtn(R.drawable.ic_arrow_up, 0f, -3f))
            val pr = LinearLayout(context)
            pr.orientation = LinearLayout.HORIZONTAL
            pr.addView(arrowBtn(R.drawable.ic_arrow_left, -3f, 0f))
            pr.addView(arrowBtn(R.drawable.ic_arrow_down, 0f, 3f))
            pr.addView(arrowBtn(R.drawable.ic_arrow_right, 3f, 0f))
            pad.addView(pr)
            selBar.addView(pad, LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(8) })

            if (settingsOpen) {
                val lpS = settingsBox.layoutParams as FrameLayout.LayoutParams
                lpS.gravity = if (m.x > width / 2f) Gravity.START else Gravity.END
                settingsBox.layoutParams = lpS
                settingsBox.visibility = View.VISIBLE
                buildSettings(m)
            } else {
                settingsBox.visibility = View.GONE
            }
        }

        private fun toggleSettings() {
            settingsOpen = !settingsOpen
            if (settingsOpen) snap = maps else listening = false
            refreshPanel()
        }

        private fun addTrigger(code: Int, allowLeft: Boolean, canClear: Boolean) {
            settingsBody.addView(lbl("Tombol Trigger"))
            val kr = row()
            val kt = TextView(context)
            kt.text = if (listening) "Tekan tombol keyboard..." else if (code == 0) "Belum ada tombol" else keyName(code)
            kt.setTextColor(MINT)
            kt.textSize = 14f
            kt.typeface = Typeface.DEFAULT_BOLD
            kt.setPadding(dp(8), dp(6), dp(4), dp(6))
            kr.addView(kt, LinearLayout.LayoutParams(0, WC, 1f))
            kr.addView(btn("ASSIGN", true) { listening = true; refreshPanel() })
            settingsBody.addView(kr)
            val hs = android.widget.HorizontalScrollView(context)
            hs.isFocusable = false
            hs.isHorizontalScrollBarEnabled = false
            val mr = row()
            if (allowLeft) mr.addView(chip("Mouse Kiri") { assignKey(MOUSE_LEFT) })
            mr.addView(chip("Mouse Kanan") { assignKey(MOUSE_RIGHT) })
            mr.addView(chip("Mouse Tengah") { assignKey(MOUSE_MID) })
            if (canClear) mr.addView(chip("Kosongkan") { listening = false; upd { it.copy(trig = 0) } })
            hs.addView(mr)
            settingsBody.addView(hs)
        }

        private fun buildSettings(m: Mapping) {
            settingsTitle.text = "Pengaturan " + selName(m)
            settingsBody.removeAllViews()
            if (m.kind == "TAP") {
                addTrigger(m.keyCode, true, false)
                settingsBody.addView(lbl("Mode"))
                val idx = modes.indexOf(m.mode).coerceAtLeast(0)
                settingsBody.addView(tabsRow(modeLabels.subList(0, 3), if (idx < 3) idx else -1) { i ->
                    upd { mm -> mm.copy(mode = modes[i]) }
                })
                settingsBody.addView(tabsRow(modeLabels.subList(3, 5), if (idx >= 3) idx - 3 else -1) { i ->
                    upd { mm -> mm.copy(mode = modes[i + 3]) }
                }, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) })
                val mh = TextView(context)
                mh.text = modeHints[idx]
                mh.setTextColor(0xFF8FA39B.toInt())
                mh.textSize = 12f
                mh.setPadding(dp(8), dp(8), dp(6), dp(4))
                settingsBody.addView(mh)
                settingsBody.addView(stepRow("Ukuran", "${(m.radius / 150f * 100).toInt()}%",
                    { upd { mm -> mm.copy(radius = (mm.radius - 15f).coerceIn(60f, 300f)) } },
                    { upd { mm -> mm.copy(radius = (mm.radius + 15f).coerceIn(60f, 300f)) } }))
            } else if (m.kind == "LOOK") {
                addTrigger(m.trig, false, true)
                settingsBody.addView(lbl("Mode"))
                settingsBody.addView(tabsRow(listOf("Toggle", "Tahan"), if (m.mode == "TAHAN") 1 else 0) { i ->
                    upd { mm -> mm.copy(mode = if (i == 1) "TAHAN" else "TOGGLE") }
                })
                val mh = TextView(context)
                mh.text = if (m.mode == "TAHAN") "Kamera aktif selama tombol ditahan." else "Tekan sekali = kamera ON, tekan lagi = OFF."
                mh.setTextColor(0xFF8FA39B.toInt())
                mh.textSize = 12f
                mh.setPadding(dp(8), dp(8), dp(6), dp(4))
                settingsBody.addView(mh)
                settingsBody.addView(stepRow("Sensitivitas X", "%.1f".format(m.sens),
                    { upd { mm -> mm.copy(sens = (mm.sens - 0.1f).coerceIn(0.1f, 10f)) } },
                    { upd { mm -> mm.copy(sens = (mm.sens + 0.1f).coerceIn(0.1f, 10f)) } }))
                settingsBody.addView(stepRow("Sensitivitas Y", "%.1f".format(m.sensY),
                    { upd { mm -> mm.copy(sensY = (mm.sensY - 0.1f).coerceIn(0.1f, 10f)) } },
                    { upd { mm -> mm.copy(sensY = (mm.sensY + 0.1f).coerceIn(0.1f, 10f)) } }))
            } else {
                settingsBody.addView(stepRow("Radius joystick", m.radius.toInt().toString(),
                    { upd { mm -> mm.copy(radius = (mm.radius - 20f).coerceIn(60f, 420f)) } },
                    { upd { mm -> mm.copy(radius = (mm.radius + 20f).coerceIn(60f, 420f)) } }))
                val mh = TextView(context)
                mh.text = "Joystick memakai tombol W A S D."
                mh.setTextColor(0xFF8FA39B.toInt())
                mh.textSize = 12f
                mh.setPadding(dp(8), dp(8), dp(6), dp(4))
                settingsBody.addView(mh)
            }
        }

        // ubah mapping terpilih tanpa membangun ulang panel (dipakai panah geser)
        private fun updQuiet(f: (Mapping) -> Mapping) {
            persist(maps.map { if (isSel(it)) f(it) else it })
            val m = selMapping()
            canvasView.selPos = if (m == null) null else PointF(m.x, m.y)
            canvasView.invalidate()
        }

        private fun upd(f: (Mapping) -> Mapping) {
            updQuiet(f)
            refreshPanel()
        }

        private fun nudge(dx: Float, dy: Float) = updQuiet { it.copy(x = it.x + dx, y = it.y + dy) }

        private fun deleteSel() {
            persist(maps.filter { !isSel(it) })
            select(NONE)
        }

        private fun assignKey(code: Int) {
            val m = selMapping()
            if (m == null || (m.kind != "TAP" && m.kind != "LOOK")) {
                Toast.makeText(context, "Tambah/pilih tombol atau FPS Look dulu", Toast.LENGTH_SHORT).show()
                return
            }
            if (m.kind == "LOOK") {
                if (maps.any { it.kind != "LOOK" && it.keyCode == code }) {
                    Toast.makeText(context, "Tombol itu sudah dipakai", Toast.LENGTH_SHORT).show()
                    return
                }
                persist(maps.map { if (it === m) it.copy(trig = code) else it })
                listening = false
                select(LOOK_KEY)
                status.text = "Pemicu FPS Look: ${keyName(code)}. Tekan di game untuk kamera ON/OFF."
                return
            }
            if (code != m.keyCode && (maps.any { it.keyCode == code } || maps.any { it.kind == "LOOK" && it.trig == code })) {
                Toast.makeText(context, "Tombol itu sudah dipakai", Toast.LENGTH_SHORT).show()
                return
            }
            persist(maps.map { if (it === m) it.copy(keyCode = code) else it })
            val keep = settingsOpen
            listening = false
            select(code)
            settingsOpen = keep
            refreshPanel()
            status.text = "Tombol ${keyName(code)} dipasang. Geser lingkaran ke posisinya."
        }

        // ----- tambah kontrol -----
        private fun center(): PointF {
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            return PointF(loc[0] + canvasView.width / 2f, loc[1] + canvasView.height / 2f)
        }

        private fun addButton() {
            addMenu.visibility = View.GONE
            if (maps.any { it.keyCode == 0 }) { select(0); return }
            val before = maps
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            val x = loc[0] + canvasView.width * 0.78f
            val y = loc[1] + canvasView.height * 0.55f
            persist(maps + Mapping(0, "TAP", x, y))
            select(0)
            snap = before
            settingsOpen = true
            listening = true
            refreshPanel()
            status.text = "Tekan tombol keyboard (atau pilih Mouse), lalu geser lingkaran ke posisi game."
        }

        private fun addJoystick() {
            addMenu.visibility = View.GONE
            if (maps.any { isJoy(it) }) { Toast.makeText(context, "Joystick sudah ada", Toast.LENGTH_SHORT).show(); select(JOY); return }
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            val x = loc[0] + canvasView.width * 0.2f
            val y = loc[1] + canvasView.height * 0.62f
            val r = canvasView.height * 0.16f
            persist(maps + listOf(
                Mapping(KeyEvent.KEYCODE_W, "UP", x, y, r), Mapping(KeyEvent.KEYCODE_S, "DOWN", x, y, r),
                Mapping(KeyEvent.KEYCODE_A, "LEFT", x, y, r), Mapping(KeyEvent.KEYCODE_D, "RIGHT", x, y, r)))
            select(JOY)
            status.text = "Joystick WASD ditambah. Geser ke joystick game-nya."
        }

        private fun addLook() {
            addMenu.visibility = View.GONE
            if (maps.any { it.kind == "LOOK" }) { Toast.makeText(context, "FPS Look sudah ada", Toast.LENGTH_SHORT).show(); select(LOOK_KEY); return }
            val before = maps
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            val x = loc[0] + canvasView.width * 0.70f
            val y = loc[1] + canvasView.height * 0.45f
            persist(maps + Mapping(LOOK_KEY, "LOOK", x, y, canvasView.height * 0.18f, "TOGGLE", 1f))
            select(LOOK_KEY)
            snap = before
            settingsOpen = true
            listening = true
            refreshPanel()
            status.text = "FPS Look: geser lingkaran ke area kamera, lalu tekan tombol keyboard (atau Mouse Kanan/Tengah) sebagai pemicu."
        }

        // ----- sentuhan di editor -----
        private fun handleTouch(e: MotionEvent): Boolean {
            val rx = e.rawX
            val ry = e.rawY
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = rx; downY = ry; lastX = rx; lastY = ry; moved = false; drag = NONE
                    val hit = maps.firstOrNull { m ->
                        val hitRadius = if (m.kind == "TAP") dp(26) * (m.radius / 150f).coerceIn(0.4f, 2f) else dp(32).toFloat()
                        (m.kind == "TAP" || m.kind == "LOOK") && hypot(m.x - rx, m.y - ry).toFloat() < hitRadius
                    }
                    if (hit != null) {
                        drag = hit.keyCode
                    } else {
                        val j = maps.firstOrNull { isJoy(it) }
                        if (j != null && hypot(j.x - rx, j.y - ry) < j.radius) drag = JOY
                    }
                    if (drag != NONE && drag != sel) select(drag)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(rx - downX, ry - downY) > dp(10)) moved = true
                    if (moved && drag != NONE) {
                        val dx = rx - lastX
                        val dy = ry - lastY
                        maps = maps.map { m -> if (isSel(m)) m.copy(x = m.x + dx, y = m.y + dy) else m }
                        val m = selMapping()
                        canvasView.selPos = if (m == null) null else PointF(m.x, m.y)
                        canvasView.invalidate()
                    }
                    lastX = rx; lastY = ry
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && drag != NONE) {
                        persist(maps)
                    } else if (!moved && drag == NONE) {
                        select(NONE)
                        addMenu.visibility = View.GONE
                    }
                    drag = NONE
                }
            }
            return true
        }

        private fun applyTemplate() {
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            val w = canvasView.width.toFloat()
            val h = canvasView.height.toFloat()
            val list = maps.toMutableList()
            fun put(code: Int, k: String, fx: Float, fy: Float, r: Float) {
                if (list.none { it.keyCode == code }) {
                    list.add(Mapping(code, k, loc[0] + fx * w, loc[1] + fy * h, r))
                }
            }
            if (list.none { isJoy(it) }) {
                val rad = h * 0.16f
                put(KeyEvent.KEYCODE_W, "UP", 0.20f, 0.62f, rad)
                put(KeyEvent.KEYCODE_S, "DOWN", 0.20f, 0.62f, rad)
                put(KeyEvent.KEYCODE_A, "LEFT", 0.20f, 0.62f, rad)
                put(KeyEvent.KEYCODE_D, "RIGHT", 0.20f, 0.62f, rad)
            }
            put(KeyEvent.KEYCODE_Q, "TAP", 0.923f, 0.081f, 150f)
            put(KeyEvent.KEYCODE_2, "TAP", 0.889f, 0.186f, 150f)
            put(KeyEvent.KEYCODE_4, "TAP", 0.936f, 0.188f, 150f)
            put(KeyEvent.KEYCODE_3, "TAP", 0.977f, 0.191f, 150f)
            put(KeyEvent.KEYCODE_TAB, "TAP", 0.686f, 0.510f, 150f)
            put(KeyEvent.KEYCODE_SPACE, "TAP", 0.975f, 0.583f, 150f)
            put(KeyEvent.KEYCODE_E, "TAP", 0.975f, 0.687f, 150f)
            put(KeyEvent.KEYCODE_1, "TAP", 0.975f, 0.861f, 150f)
            put(KeyEvent.KEYCODE_X, "TAP", 0.040f, 0.740f, 150f)
            put(KeyEvent.KEYCODE_CAPS_LOCK, "TAP", 0.040f, 0.825f, 150f)
            put(KeyEvent.KEYCODE_Z, "TAP", 0.040f, 0.913f, 150f)
            put(KeyEvent.KEYCODE_ALT_LEFT, "TAP", 0.624f, 0.836f, 150f)
            put(KeyEvent.KEYCODE_SHIFT_LEFT, "TAP", 0.866f, 0.946f, 150f)
            put(KeyEvent.KEYCODE_R, "TAP", 0.950f, 0.938f, 150f)
            persist(list)
            canvasView.invalidate()
            status.text = "Template dipasang. Tap tanda untuk atur, geser ke tombol game-nya, lalu Selesai."
        }
    }
}
