package com.example.keymapper

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Penyuntik sentuhan (diisi oleh OverlayService; di tes diisi tiruan).
interface LookSink {
    fun down(id: Int, x: Float, y: Float): Boolean
    fun move(id: Int, x: Float, y: Float)
    fun up(id: Int, x: Float, y: Float)
}

interface LookClock {
    fun now(): Long
    fun post(delayMs: Long, r: Runnable)
    fun cancel(r: Runnable)
}

/**
 * FPS Look: SATU jari virtual yang menggerakkan kamera.
 *
 *  - Mode kamera : jari ada di sekitar marker Look (lookX, lookY) dan bergerak mengikuti mouse.
 *  - Mode tembak : saat klik kiri ditahan (dan tombol tembak dipetakan HOLD), jari pindah ke tombol
 *                  tembak (fireX, fireY) dan bergeser dari sana. Game seperti Free Fire memakai jari
 *                  tombol tembak sebagai pengendali kamera, jadi kamera tetap ikut saat menembak
 *                  (tanpa dua jari yang berebut kamera = tanpa lompatan ke atas).
 *
 * Aturan yang dijaga (dan diuji di tes):
 *  - paling banyak satu jari menyentuh pada satu waktu; setiap DOWN memakai id baru (bergantian A/B);
 *  - setiap DOWN punya tepat satu UP; MOVE hanya untuk jari yang sedang menyentuh;
 *  - posisi jari selalu di dalam layar dan di dalam jangkauan;
 *  - saat jari mentok batas: UP, jeda singkat, DOWN baru di jangkar; sisa gerakan TIDAK hilang;
 *  - reset()/setActive(false) selalu mengangkat jari dan membatalkan semua timer.
 */
class FpsLook(private val sink: LookSink, private val clock: LookClock) {
    companion object {
        const val ID_A = 9
        const val ID_B = 10
        const val GAP_MS = 16L        // jeda antara UP dan DOWN baru saat recenter
        const val GAP_MAX_MS = 100L   // pengaman: jeda tidak boleh tersangkut
    }

    // ---- konfigurasi (diisi service) ----
    var lookX = 0f
    var lookY = 0f
    var fireX = 0f
    var fireY = 0f
    var fireEnabled = false
    var sensX = 1f
    var sensY = 1f
    var screenW = 0
    var screenH = 0
    var margin = 24f

    // ---- keadaan ----
    var active = false
        private set
    var firing = false
        private set
    private var id = -1
    private var px = 0f
    private var py = 0f
    private var gap = false
    private var gapAt = 0L
    private var pendX = 0f
    private var pendY = 0f
    private var nextId = ID_A
    private val regrabTask = Runnable { regrab() }

    val fingerDown: Boolean get() = id >= 0
    val pendingX: Float get() = pendX
    val pendingY: Float get() = pendY
    val fingerX: Float get() = px
    val fingerY: Float get() = py
    val inGap: Boolean get() = gap

    fun ownsFire(): Boolean = active && fireEnabled

    private fun ax(): Float = if (firing) fireX else lookX
    private fun ay(): Float = if (firing) fireY else lookY

    private fun reach(): Float {
        val base = if (screenW > 0 && screenH > 0) min(screenW, screenH).toFloat() else 1080f
        return if (firing) max(0.35f * base, 220f) else max(0.45f * base, 300f)
    }

    fun setActive(on: Boolean) {
        if (on == active) return
        if (!on) release()
        active = on
    }

    fun reset() {
        release()
        active = false
    }

    // Dipanggil setelah konfigurasi berubah (mis. tombol tembak dimatikan saat sedang menembak).
    fun configChanged() {
        if (firing && !(active && fireEnabled)) {
            clock.cancel(regrabTask)
            gap = false; pendX = 0f; pendY = 0f
            if (id >= 0) { sink.up(id, px, py); id = -1 }
            firing = false
        }
    }

    private fun release() {
        clock.cancel(regrabTask)
        if (id >= 0) { sink.up(id, px, py); id = -1 }
        gap = false
        firing = false
        pendX = 0f; pendY = 0f
    }

    fun fire(down: Boolean) {
        if (!ownsFire()) return
        if (down) {
            if (firing) return
            clock.cancel(regrabTask)
            gap = false; pendX = 0f; pendY = 0f
            if (id >= 0) { sink.up(id, px, py); id = -1 }   // jari kamera pindah ke tombol tembak
            firing = true
            grab()
        } else {
            if (!firing) return
            clock.cancel(regrabTask)
            gap = false; pendX = 0f; pendY = 0f
            if (id >= 0) { sink.up(id, px, py); id = -1 }
            firing = false
        }
    }

    fun onDelta(dx: Float, dy: Float) {
        if (!active) return
        val sx = dx * sensX
        val sy = dy * sensY
        if (sx == 0f && sy == 0f) return
        if (gap) {
            pendX += sx; pendY += sy
            capPending()
            if (clock.now() - gapAt > GAP_MAX_MS) regrab()
            return
        }
        if (id < 0 && !grab()) return
        applyMove(sx, sy)
    }

    private fun grab(): Boolean {
        val newId = nextId
        val x = ax()
        val y = ay()
        if (!sink.down(newId, x, y)) return false
        nextId = if (newId == ID_A) ID_B else ID_A
        id = newId
        px = x; py = y
        return true
    }

    private fun applyMove(sx: Float, sy: Float) {
        val nx = px + sx
        val ny = py + sy
        val a = ax()
        val b = ay()
        val hasScreen = screenW > 0 && screenH > 0
        val minX = if (hasScreen) min(margin, a) else -1e9f
        val maxX = if (hasScreen) max(screenW - margin, a) else 1e9f
        val minY = if (hasScreen) min(margin, b) else -1e9f
        val maxY = if (hasScreen) max(screenH - margin, b) else 1e9f
        var tx = nx.coerceIn(minX, maxX)
        var ty = ny.coerceIn(minY, maxY)
        val r = reach()
        val d = hypot(tx - a, ty - b)
        if (d > r) {
            val k = r / d
            tx = a + (tx - a) * k
            ty = b + (ty - b) * k
        }
        if (tx != px || ty != py) { sink.move(id, tx, ty); px = tx; py = ty }
        if (tx != nx || ty != ny) {
            // mentok: simpan sisa gerakan, angkat jari, pasang lagi di jangkar
            pendX += nx - tx; pendY += ny - ty
            capPending()
            recenter()
        }
    }

    private fun capPending() {
        val c = reach() * 2f
        pendX = pendX.coerceIn(-c, c)
        pendY = pendY.coerceIn(-c, c)
    }

    private fun recenter() {
        if (id >= 0) { sink.up(id, px, py); id = -1 }
        gap = true
        gapAt = clock.now()
        clock.cancel(regrabTask)
        clock.post(GAP_MS, regrabTask)
    }

    private fun regrab() {
        clock.cancel(regrabTask)
        if (!gap) return
        gap = false
        if (!active) { pendX = 0f; pendY = 0f; return }
        if (!grab()) { pendX = 0f; pendY = 0f; return }
        val dx = pendX
        val dy = pendY
        pendX = 0f; pendY = 0f
        if (dx != 0f || dy != 0f) applyMove(dx, dy)
    }
}
