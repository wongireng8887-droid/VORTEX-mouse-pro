package com.example.keymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

class KeyMapService : AccessibilityService() {
    companion object { var instance: KeyMapService? = null }

    private var maps: List<Mapping> = emptyList()
    var enabledMap = true
        private set
    private var editing = false
    private val pressed = HashSet<Int>()

    // sentuhan yang sedang menempel di layar: id -> (stroke, titik akhir)
    private val active = HashMap<String, Pair<GestureDescription.StrokeDescription, PointF>>()
    private var desired: Map<String, Pair<PointF, PointF>> = emptyMap()
    private var inFlight = false
    private var dirty = false

    // overlay
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var editor: EditorView? = null
    private var bubbleX = 40
    private var bubbleY = 260

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        maps = Store.load(this)
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        maps = Store.load(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- input keyboard ----------
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (editing) return false
        if (e.keyCode == KeyEvent.KEYCODE_F8) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) setMapping(!enabledMap)
            return true
        }
        if (!enabledMap) return false
        if (maps.none { it.keyCode == e.keyCode }) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) pressed.add(e.keyCode)
            KeyEvent.ACTION_UP -> pressed.remove(e.keyCode)
        }
        refresh()
        return true
    }

    private fun setMapping(on: Boolean) {
        enabledMap = on
        if (!on) { pressed.clear(); refresh() }
        bubble?.alpha = if (on) 1f else 0.5f
        Toast.makeText(this, if (on) "Mapping ON" else "Mapping OFF", Toast.LENGTH_SHORT).show()
    }

    // ---------- injeksi sentuhan ----------
    private fun refresh() {
        val t = LinkedHashMap<String, Pair<PointF, PointF>>()
        var jx = 0f; var jy = 0f; var joy: Mapping? = null
        for (m in maps) {
            if (m.keyCode !in pressed) continue
            if (m.kind == "TAP") {
                t["k${m.keyCode}"] = PointF(m.x, m.y) to PointF(m.x, m.y)
            } else {
                joy = m
                when (m.kind) { "UP" -> jy -= 1f; "DOWN" -> jy += 1f; "LEFT" -> jx -= 1f; "RIGHT" -> jx += 1f }
            }
        }
        joy?.let {
            val len = hypot(jx, jy)
            if (len > 0f) t["joy"] = PointF(it.x, it.y) to PointF(it.x + jx / len * it.radius, it.y + jy / len * it.radius)
        }
        desired = t
        pump()
    }

    private fun pump() {
        if (inFlight) { dirty = true; return }
        val b = GestureDescription.Builder()
        var n = 0
        val next = HashMap<String, Pair<GestureDescription.StrokeDescription, PointF>>()
        for ((id, st) in desired) {
            val (start, target) = st
            val old = active[id]
            val path = Path()
            val stroke = if (old == null) {
                path.moveTo(start.x, start.y); path.lineTo(target.x, target.y)
                GestureDescription.StrokeDescription(path, 0, 10, true)
            } else {
                path.moveTo(old.second.x, old.second.y); path.lineTo(target.x, target.y)
                old.first.continueStroke(path, 0, 10, true)
            }
            b.addStroke(stroke); n++
            next[id] = stroke to target
        }
        for ((id, old) in active) {
            if (id in desired) continue
            val path = Path(); path.moveTo(old.second.x, old.second.y)
            b.addStroke(old.first.continueStroke(path, 0, 10, false)); n++
        }
        active.clear(); active.putAll(next)
        if (n == 0) return
        inFlight = true
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done() }
            override fun onCancelled(g: GestureDescription?) { active.clear(); done() }
        }, null)
        if (!ok) inFlight = false
    }

    private fun done() {
        inFlight = false
        if (dirty) { dirty = false; pump() }
    }

    // ---------- overlay: bubble ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun overlayType(): Int =
        if (Settings.canDrawOverlays(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun isBubbleShown(): Boolean = bubble != null

    fun showBubble() {
        if (bubble != null) return
        val v = TextView(this).apply {
            text = "V"; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE60F6B4B.toInt()); setStroke(dp(2), 0xFF34D399.toInt())
            }
            alpha = if (enabledMap) 1f else 0.5f
        }
        val lp = WindowManager.LayoutParams(dp(52), dp(52), overlayType(),
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
                    MotionEvent.ACTION_UP -> if (!moved) { if (panel == null) openPanel() else closePanel() }
                }
                return true
            }
        })
        try { wm?.addView(v, lp); bubble = v } catch (e: Exception) {
            Toast.makeText(this, "Gagal menampilkan bubble", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideBubble() {
        closePanel(); closeEditor()
        bubble?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        bubble = null
    }

    private fun menuItem(t: String, a: () -> Unit): TextView = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(0xFF1F2D28.toInt(), 10)
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(4) }
        setOnClickListener { a() }
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
        col.addView(menuItem(if (enabledMap) "Mapping: ON" else "Mapping: OFF") { setMapping(!enabledMap); closePanel() })
        col.addView(menuItem("Edit tombol") { closePanel(); openEditor() })
        col.addView(menuItem("Sembunyikan") { hideBubble() })
        try { wm?.addView(col, lp); panel = col } catch (e: Exception) {}
    }

    private fun closePanel() {
        panel?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        panel = null
    }

    // ---------- overlay: editor tombol ----------
    private fun displayPos(m: Mapping): PointF = when (m.kind) {
        "UP" -> PointF(m.x, m.y - m.radius)
        "DOWN" -> PointF(m.x, m.y + m.radius)
        "LEFT" -> PointF(m.x - m.radius, m.y)
        "RIGHT" -> PointF(m.x + m.radius, m.y)
        else -> PointF(m.x, m.y)
    }

    private fun persist(list: List<Mapping>) {
        Store.save(this, list); maps = list
    }

    private fun openEditor() {
        if (editor != null) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan izin 'Tampilkan di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
            return
        }
        editing = true
        pressed.clear(); refresh()
        val root = EditorView(this)
        val lp = WindowManager.LayoutParams(MP, MP, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm?.addView(root, lp); editor = root } catch (e: Exception) { editing = false }
    }

    private fun closeEditor() {
        editor?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        editor = null
        editing = false
    }

    private inner class EditorView(c: Context) : FrameLayout(c) {
        var pendingKey = 0
        var kind = "TAP"
        private val status = TextView(c)
        private val kindViews = ArrayList<TextView>()
        private val kinds = listOf("TAP", "UP", "DOWN", "LEFT", "RIGHT")
        private val kindLabels = listOf("Tap", "↑", "↓", "←", "→")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val canvasView: View

        init {
            setBackgroundColor(0x66000000)
            isFocusable = true
            isFocusableInTouchMode = true
            canvasView = object : View(c) {
                override fun onDraw(cv: Canvas) { drawMarkers(cv, this) }
                override fun onTouchEvent(e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_UP) onTap(e.rawX, e.rawY)
                    return true
                }
            }
            addView(canvasView, FrameLayout.LayoutParams(MP, MP))

            val bar = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(0xF2111A17.toInt(), 14)
            }
            status.setTextColor(Color.WHITE)
            status.textSize = 12f
            status.text = "Tekan tombol keyboard → pilih jenis → tap posisi di layar.\nTap tanda hijau = hapus."
            bar.addView(status)
            kinds.forEachIndexed { i, k ->
                val tv = chip(kindLabels[i]) { kind = k; refreshKinds() }
                kindViews.add(tv); bar.addView(tv)
            }
            bar.addView(chip("Selesai") { closeEditor() })
            addView(bar, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
            refreshKinds()

            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    pendingKey = code
                    status.text = "Tombol: ${keyName(code)}\nSekarang tap posisinya di layar."
                }
                true
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        private fun chip(t: String, a: () -> Unit): TextView = TextView(context).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(6) }
            setOnClickListener { a() }
        }

        private fun refreshKinds() {
            kindViews.forEachIndexed { i, tv ->
                tv.background = rounded(if (kinds[i] == kind) 0xFF0F6B4B.toInt() else 0xFF1F2D28.toInt(), 10)
            }
        }

        private fun onTap(rx: Float, ry: Float) {
            val list = maps.toMutableList()
            if (pendingKey == 0) {
                val hit = list.firstOrNull {
                    val p = displayPos(it)
                    hypot(p.x - rx, p.y - ry) < dp(36)
                }
                if (hit != null) {
                    list.remove(hit); persist(list); canvasView.invalidate()
                } else {
                    Toast.makeText(context, "Tekan tombol keyboard dulu", Toast.LENGTH_SHORT).show()
                }
                return
            }
            list.removeAll { it.keyCode == pendingKey }
            val joy = list.firstOrNull { it.kind != "TAP" }
            val m = if (kind != "TAP" && joy != null) Mapping(pendingKey, kind, joy.x, joy.y, joy.radius)
                    else Mapping(pendingKey, kind, rx, ry)
            list.add(m); persist(list)
            pendingKey = 0
            status.text = "Tersimpan. Tekan tombol lain, atau Selesai.\nTap tanda hijau = hapus."
            canvasView.invalidate()
        }

        private fun drawMarkers(cv: Canvas, v: View) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val seenJoy = HashSet<String>()
            for (m in maps) {
                val p = displayPos(m)
                val px = p.x - loc[0]
                val py = p.y - loc[1]
                if (m.kind != "TAP" && seenJoy.add("${m.x},${m.y}")) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2).toFloat()
                    paint.color = 0x8834D399.toInt()
                    cv.drawCircle(m.x - loc[0], m.y - loc[1], m.radius, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = 0xCC0F6B4B.toInt()
                cv.drawCircle(px, py, dp(26).toFloat(), paint)
                paint.color = Color.WHITE
                paint.textSize = dp(12).toFloat()
                paint.textAlign = Paint.Align.CENTER
                cv.drawText(keyName(m.keyCode), px, py + dp(4), paint)
            }
        }
    }
}
