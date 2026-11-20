package com.example.keymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PointF
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.hypot

class KeyMapService : AccessibilityService() {
    private var maps: List<Mapping> = emptyList()
    private var enabledMap = true
    private val pressed = HashSet<Int>()

    // sentuhan yang sedang menempel di layar: id -> (stroke, titik akhir)
    private val active = HashMap<String, Pair<GestureDescription.StrokeDescription, PointF>>()
    // sentuhan yang diinginkan: id -> (titik awal, titik tujuan)
    private var desired: Map<String, Pair<PointF, PointF>> = emptyMap()
    private var inFlight = false
    private var dirty = false

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        maps = Store.load(this)
    }

    override fun onServiceConnected() {
        maps = Store.load(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        // F8 = nyala/mati mapping
        if (e.keyCode == KeyEvent.KEYCODE_F8) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                enabledMap = !enabledMap
                if (!enabledMap) { pressed.clear(); refresh() }
                Toast.makeText(this, if (enabledMap) "Mapping ON" else "Mapping OFF", Toast.LENGTH_SHORT).show()
            }
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
        // lepas sentuhan yang tombolnya sudah dilepas
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
}
