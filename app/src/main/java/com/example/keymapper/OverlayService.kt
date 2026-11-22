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
    private var flushPending = false
    private val lookIdle = Runnable { lookActive = false; refresh() }
    private val flush = Runnable {
        flushPending = false
        val dx = accX; val dy = accY
        accX = 0f; accY = 0f
        applyLook(dx, dy)
    }
    private var prevButtons = 0

    // sentuhan lewat Shizuku
    private var desired: Map<String, Pair<PointF, PointF>> = emptyMap()
    private val live = HashMap<String, Pair<Int, PointF>>()

    // overlay
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var panel: LinearLayout? = null
    private var editor: EditorView? = null
    private var hud: MapCanvas? = null
    private var capture: CaptureView? = null
    private var bubbleX = 40
    private var bubbleY = 260

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
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        ShizukuBridge.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (intent?.action == ACTION_HIDE) {
            shutdown()
        } else {
            showBubble()
            val ready = Injector.ready
            Toast.makeText(this, if (ready) "Siap. Tekan tombol keyboard / gerakkan mouse." else "Belum terhubung. Buka aplikasi, pilih cara sambung.",
                Toast.LENGTH_LONG).show()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        instance = null
        super.onDestroy()
    }

    private fun shutdown() {
        hideBubble()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ---------- input keyboard & mouse ----------
    private fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        val ms = maps.filter { it.keyCode == code && it.kind != "LOOK" }
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
        if (!enabledMap || editing) return
        lastEvt = "Mouse gerak"
        accX += dx; accY += dy
        if (!flushPending) { flushPending = true; handler.postDelayed(flush, 8) }
    }

    private fun applyLook(dx: Float, dy: Float) {
        val m = maps.firstOrNull { it.kind == "LOOK" } ?: return
        if (!lookActive) { lookActive = true; lookX = m.x; lookY = m.y }
        lookX += dx * m.sens
        lookY += dy * m.sens
        if (hypot(lookX - m.x, lookY - m.y) > m.radius) {
            lookActive = false; refresh()
            lookActive = true; lookX = m.x; lookY = m.y
        }
        refresh()
        handler.removeCallbacks(lookIdle)
        handler.postDelayed(lookIdle, 250)
    }

    private fun onMouseButtons(state: Int) {
        val changed = state xor prevButtons
        prevButtons = state
        val pairs = listOf(
            MotionEvent.BUTTON_PRIMARY to MOUSE_LEFT,
            MotionEvent.BUTTON_SECONDARY to MOUSE_RIGHT,
            MotionEvent.BUTTON_TERTIARY to MOUSE_MID)
        for ((bit, code) in pairs) {
            if ((changed and bit) != 0) handleKey(code, (state and bit) != 0, 0)
        }
    }

    private fun setMapping(on: Boolean) {
        enabledMap = on
        if (!on) {
            pressed.clear(); pulsing.clear(); repeaters.clear()
            handler.removeCallbacksAndMessages(null)
            lookActive = false; accX = 0f; accY = 0f; flushPending = false
            refresh()
        }
        bubble?.alpha = if (on) 1f else 0.5f
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
                    onMouseMove(e.x, e.y)
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
            if (hasWindowFocus) requestPointerCapture()
        }
    }

    private fun updateCapture() {
        val need = bubble != null && enabledMap && !editing && maps.isNotEmpty()
        if (need && capture == null) {
            val v = CaptureView(this)
            val lp = WindowManager.LayoutParams(1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            try { wm?.addView(v, lp); capture = v; v.requestFocus() } catch (e: Exception) {}
        } else if (!need && capture != null) {
            capture?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
            capture = null
            handler.removeCallbacks(lookIdle)
            if (lookActive) { lookActive = false; refresh() }
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
                    "TAP" -> drawKey(cv, m.x - loc[0], m.y - loc[1], shortName(m.keyCode), m.keyCode == selKey, hot)
                    "LOOK" -> drawLook(cv, m, loc)
                }
            }
            selPos?.let { drawCross(cv, it, loc) }
        }

        private fun drawKey(cv: Canvas, x: Float, y: Float, name: String, sel: Boolean, hot: Boolean) {
            paint.style = Paint.Style.FILL
            paint.color = if (hot) 0xE60F8F63.toInt() else 0xB3000000.toInt()
            cv.drawCircle(x, y, dp(26).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (sel) dp(3).toFloat() else dp(1).toFloat()
            paint.color = if (sel || hot) MINT else 0xAAFFFFFF.toInt()
            cv.drawCircle(x, y, dp(26).toFloat(), paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = (if (name.length > 3) dp(10) else dp(15)).toFloat()
            cv.drawText(name, x, y + paint.textSize * 0.35f, paint)
        }

        private fun drawLook(cv: Canvas, m: Mapping, loc: IntArray) {
            val cx = m.x - loc[0]
            val cy = m.y - loc[1]
            val sel = m.keyCode == selKey
            paint.style = Paint.Style.FILL
            paint.color = 0x33000000
            cv.drawCircle(cx, cy, m.radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = if (sel || lookActive) MINT else 0xAAFFFFFF.toInt()
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(6).toFloat()), 0f)
            cv.drawCircle(cx, cy, m.radius, paint)
            paint.pathEffect = null
            drawKey(cv, cx, cy, "Look", sel, lookActive)
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
                layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            }
            addView(mouseIcon)
            alpha = if (enabledMap) 1f else 0.55f
        }
        val lp = WindowManager.LayoutParams(dp(60), dp(60), overlayType(),
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
                        if (panel == null) openPanel() else closePanel()
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
        updateCapture()
        hideHud()
        Injector.releaseAll(); live.clear()
    }

    private fun menuItem(t: String, a: () -> Unit): TextView = chip(t, a).apply {
        layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) }
    }

    private fun openPanel() {
        val lp = WindowManager.LayoutParams(WC, WC, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = bubbleX + dp(58); y = bubbleY
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = rounded(0xF2111A17.toInt(), 14)
        }
        val shz = if (Injector.ready) "OK (" + Injector.mode + ")" else "putus"
        val mouse = if (capture?.hasPointerCapture() == true) "OK" else "belum"
        col.addView(TextView(this).apply {
            text = "Sambungan: $shz | Mouse: $mouse\nTerakhir: $lastEvt"
            setTextColor(0xFF9FB3AB.toInt()); textSize = 11f
        })
        col.addView(menuItem(if (enabledMap) "Mapping: ON" else "Mapping: OFF") { setMapping(!enabledMap); closePanel() })
        col.addView(menuItem(if (hudVisible) "Tombol di layar: ON" else "Tombol di layar: OFF") {
            hudVisible = !hudVisible; applyHudVisibility(); closePanel()
        })
        val game = Store.currentGame(this)
        if (game.isNotEmpty()) {
            col.addView(menuItem("Profil: " + Store.activeProfile(this, game)) {
                val names = Store.profiles(this, game)
                if (names.size < 2) {
                    Toast.makeText(this, "Baru ada 1 profil. Tambah di tab Pemetaan.", Toast.LENGTH_SHORT).show()
                } else {
                    val next = names[(names.indexOf(Store.activeProfile(this, game)) + 1) % names.size]
                    Store.setActiveProfile(this, game, next)
                    Toast.makeText(this, "Profil: $next", Toast.LENGTH_SHORT).show()
                }
                closePanel()
            })
        }
        col.addView(menuItem("Edit tombol") { closePanel(); openEditor() })
        col.addView(menuItem("Sembunyikan") { shutdown() })
        try { wm?.addView(col, lp); panel = col } catch (e: Exception) {}
    }

    private fun closePanel() {
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
        private val panelBox = LinearLayout(c)
        private val addMenu = LinearLayout(c)
        private var sel = NONE
        private var drag = NONE
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private val modes = listOf("HOLD", "TAP", "REPEAT", "DOUBLE", "RELEASE")
        private val modeLabels = listOf("Hold", "Tap", "Repeat", "Double", "Release")
        private val hint = "Tambah tombol, lalu geser ke posisinya. Tap tanda untuk atur."

        init {
            setBackgroundColor(0x66000000)
            isFocusable = true
            isFocusableInTouchMode = true
            canvasView.touch = { e -> handleTouch(e) }
            addView(canvasView, FrameLayout.LayoutParams(MP, MP))

            val top = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(0xF2111A17.toInt(), 14)
            }
            status.setTextColor(Color.WHITE)
            status.textSize = 12f
            status.text = hint
            top.addView(status)
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(chipIc("Tambah", R.drawable.ic_add) { addMenu.visibility = if (addMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            row.addView(chip("Template") { applyTemplate() })
            row.addView(chipIc("Selesai", R.drawable.ic_check) { closeEditor() })
            top.addView(row)
            addMenu.orientation = LinearLayout.HORIZONTAL
            addMenu.visibility = View.GONE
            addMenu.addView(chip("Tombol") { addButton() })
            addMenu.addView(chip("Joystick") { addJoystick() })
            addMenu.addView(chip("FPS Look") { addLook() })
            top.addView(addMenu)
            addView(top, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

            panelBox.orientation = LinearLayout.VERTICAL
            panelBox.setPadding(dp(12), dp(8), dp(12), dp(10))
            panelBox.background = rounded(0xF2111A17.toInt(), 14)
            panelBox.visibility = View.GONE
            addView(panelBox, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })

            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) assignKey(code)
                true
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        // ----- pemilihan -----
        private fun isSel(m: Mapping): Boolean = when (sel) {
            NONE -> false
            JOY -> isJoy(m)
            else -> m.keyCode == sel && !isJoy(m)
        }

        private fun selMapping(): Mapping? = maps.firstOrNull { isSel(it) }

        private fun select(s: Int) {
            sel = s
            canvasView.selKey = s
            val m = selMapping()
            canvasView.selPos = if (m == null) null else PointF(m.x, m.y)
            canvasView.invalidate()
            refreshPanel()
        }

        private fun row(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        private fun refreshPanel() {
            panelBox.removeAllViews()
            val m = selMapping()
            if (sel == NONE || m == null) { panelBox.visibility = View.GONE; return }
            panelBox.visibility = View.VISIBLE
            val title = when {
                sel == JOY -> "Joystick - radius ${m.radius.toInt()}"
                m.kind == "LOOK" -> "FPS Look - sens ${"%.1f".format(m.sens)} - area ${m.radius.toInt()}"
                m.keyCode == 0 -> "Tombol baru - tekan tombol keyboard"
                else -> "Tombol ${keyName(m.keyCode)}"
            }
            val t = TextView(context)
            t.text = title; t.setTextColor(Color.WHITE); t.textSize = 13f; t.typeface = Typeface.DEFAULT_BOLD
            panelBox.addView(t)

            val r1 = row()
            r1.addView(chipIc("Hapus", R.drawable.ic_trash) { deleteSel() })
            if (m.kind == "TAP") {
                r1.addView(chip("Mouse Kiri") { assignKey(MOUSE_LEFT) })
                r1.addView(chip("Mouse Kanan") { assignKey(MOUSE_RIGHT) })
            }
            panelBox.addView(r1)

            val r2 = row()
            if (m.kind == "TAP") {
                modes.forEachIndexed { i, md ->
                    val cc = chip(modeLabels[i]) { upd { it.copy(mode = md) } }
                    if (m.mode == md) cc.background = rounded(0xFF0F6B4B.toInt(), 10)
                    r2.addView(cc)
                }
            } else if (m.kind == "LOOK") {
                r2.addView(lbl("Sens"))
                r2.addView(iconChip(R.drawable.ic_minus) { upd { it.copy(sens = (it.sens - 0.2f).coerceIn(0.2f, 5f)) } })
                r2.addView(iconChip(R.drawable.ic_add) { upd { it.copy(sens = (it.sens + 0.2f).coerceIn(0.2f, 5f)) } })
                r2.addView(lbl("Area"))
                r2.addView(iconChip(R.drawable.ic_minus) { upd { it.copy(radius = (it.radius - 20f).coerceIn(60f, 600f)) } })
                r2.addView(iconChip(R.drawable.ic_add) { upd { it.copy(radius = (it.radius + 20f).coerceIn(60f, 600f)) } })
            } else {
                r2.addView(lbl("Radius"))
                r2.addView(iconChip(R.drawable.ic_minus) { upd { it.copy(radius = (it.radius - 20f).coerceIn(60f, 420f)) } })
                r2.addView(iconChip(R.drawable.ic_add) { upd { it.copy(radius = (it.radius + 20f).coerceIn(60f, 420f)) } })
            }
            panelBox.addView(r2)

            val r3 = row()
            r3.addView(lbl("Geser"))
            r3.addView(iconChip(R.drawable.ic_arrow_left) { nudge(-3f, 0f) })
            r3.addView(iconChip(R.drawable.ic_arrow_up) { nudge(0f, -3f) })
            r3.addView(iconChip(R.drawable.ic_arrow_down) { nudge(0f, 3f) })
            r3.addView(iconChip(R.drawable.ic_arrow_right) { nudge(3f, 0f) })
            panelBox.addView(r3)
        }

        private fun upd(f: (Mapping) -> Mapping) {
            persist(maps.map { if (isSel(it)) f(it) else it })
            val m = selMapping()
            canvasView.selPos = if (m == null) null else PointF(m.x, m.y)
            canvasView.invalidate()
            refreshPanel()
        }

        private fun nudge(dx: Float, dy: Float) = upd { it.copy(x = it.x + dx, y = it.y + dy) }

        private fun deleteSel() {
            persist(maps.filter { !isSel(it) })
            select(NONE)
        }

        private fun assignKey(code: Int) {
            val m = selMapping()
            if (m == null || m.kind != "TAP") {
                Toast.makeText(context, "Tambah/pilih tombol dulu", Toast.LENGTH_SHORT).show()
                return
            }
            if (code != m.keyCode && maps.any { it.keyCode == code }) {
                Toast.makeText(context, "Tombol itu sudah dipakai", Toast.LENGTH_SHORT).show()
                return
            }
            persist(maps.map { if (it === m) it.copy(keyCode = code) else it })
            select(code)
            status.text = "Tombol ${keyName(code)} dipasang. Geser ke posisinya."
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
            val c = center()
            persist(maps + Mapping(0, "TAP", c.x, c.y))
            select(0)
            status.text = "Tekan tombol keyboard (atau Mouse Kiri/Kanan), lalu geser ke posisinya."
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
            val loc = IntArray(2)
            canvasView.getLocationOnScreen(loc)
            val x = loc[0] + canvasView.width * 0.70f
            val y = loc[1] + canvasView.height * 0.45f
            persist(maps + Mapping(LOOK_KEY, "LOOK", x, y, canvasView.height * 0.18f, "HOLD", 1f))
            select(LOOK_KEY)
            status.text = "FPS Look: geser lingkaran ke area putar kamera. Gerakkan mouse untuk memutar."
        }

        // ----- sentuhan di editor -----
        private fun handleTouch(e: MotionEvent): Boolean {
            val rx = e.rawX
            val ry = e.rawY
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = rx; downY = ry; lastX = rx; lastY = ry; moved = false; drag = NONE
                    val hit = maps.firstOrNull { (it.kind == "TAP" || it.kind == "LOOK") && hypot(it.x - rx, it.y - ry) < dp(32) }
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
