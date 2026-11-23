package com.example.keymapper

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Mesin FPS Look (dibuat ulang).
 *
 * Prinsip:
 *  - Berjalan di thread sendiri (prioritas tinggi) sehingga tidak ikut tersendat oleh thread UI / HUD.
 *  - Event mouse (sudah dijumlahkan per frame oleh OverlayService) hanya MENAMBAH sisa gerakan (pend).
 *    Thread ini mengeluarkannya secara halus tiap 4 ms (~250 Hz), jadi kecepatan kamera rata
 *    walau event dari sistem datang berkelompok per frame.
 *  - Jari virtual dipasang di marker, digeser mengikuti gerakan, dan DIGANTI (angkat + pasang di marker
 *    dengan id jari baru) ketika mendekati batas. Sisa gerakan yang melewati batas tidak hilang:
 *    dibawa ke jari berikutnya. Saat mouse diam dan jari sudah jauh dari marker, jari diganti lebih
 *    awal supaya gerakan berikutnya punya ruang penuh.
 *  - Id jari look (20 / 21) terpisah dari id tombol (0..8), jadi tidak bisa bertabrakan dengan sentuhan
 *    tombol tembak, joystick, dll.
 *
 * Semua keadaan dilindungi satu kunci (lock); Injector.touch() aman dipanggil dari thread mana pun.
 */
class LookEngine(
    private val screenSize: () -> Pair<Int, Int>,
    private val marginPx: () -> Float
) {
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var active = false
    @Volatile private var alive = true

    private var mx = 0f
    private var my = 0f
    private var pendX = 0f
    private var pendY = 0f
    private var down = false
    private var slot = SLOT_B
    private var fx = 0f
    private var fy = 0f
    private var gapUntil = 0L
    private var lastMoveAt = 0L
    private var sw = 0
    private var sh = 0
    private var margin = 24f

    /** Jumlah penggantian jari sejak aktif (untuk diagnosa di menu). */
    @Volatile var recenters = 0
        private set

    fun start(markerX: Float, markerY: Float) {
        synchronized(lock) {
            val (w, h) = screenSize()
            sw = w
            sh = h
            margin = marginPx()
            mx = markerX
            my = markerY
            pendX = 0f
            pendY = 0f
            down = false
            gapUntil = 0L
            lastMoveAt = SystemClock.uptimeMillis()
            recenters = 0
            active = true
            if (thread?.isAlive != true) {
                val t = Thread({ loop() }, "VortexLook")
                t.isDaemon = true
                thread = t
                t.start()
            }
        }
        LockSupport.unpark(thread)
    }

    fun stop() {
        synchronized(lock) {
            active = false
            pendX = 0f
            pendY = 0f
            liftLocked()
        }
    }

    /** Dipanggil saat sambungan injeksi putus: jari dianggap sudah hilang di sisi shell. */
    fun resetState() {
        synchronized(lock) {
            down = false
            pendX = 0f
            pendY = 0f
        }
    }

    fun shutdown() {
        alive = false
        stop()
        LockSupport.unpark(thread)
    }

    /** dx, dy = gerakan mouse mentah; sx, sy = sensitivitas; marker = titik awal jari. */
    fun addDelta(dx: Float, dy: Float, sx: Float, sy: Float, markerX: Float, markerY: Float) {
        synchronized(lock) {
            if (!active) return
            mx = markerX
            my = markerY
            pendX = (pendX + dx * sx).coerceIn(-MAX_PEND, MAX_PEND)
            pendY = (pendY + dy * sy).coerceIn(-MAX_PEND, MAX_PEND)
        }
    }

    private fun loop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (e: Throwable) {}
        while (alive) {
            if (!active) {
                LockSupport.park(this)
                continue
            }
            try {
                synchronized(lock) { tickLocked(SystemClock.uptimeMillis()) }
            } catch (e: Throwable) {
                // satu tick gagal tidak boleh mematikan mesin
            }
            LockSupport.parkNanos(TICK_NS)
        }
    }

    private fun tickLocked(now: Long) {
        if (!active || now < gapUntil) return

        var ex = pendX * SMOOTH
        var ey = pendY * SMOOTH
        if (abs(pendX) < 1f) ex = pendX      // sisa kecil langsung dihabiskan
        if (abs(pendY) < 1f) ey = pendY
        pendX -= ex
        pendY -= ey

        if (ex == 0f && ey == 0f) {
            idleCheckLocked(now)
            return
        }
        lastMoveAt = now

        if (!down && !downLocked()) {
            // belum tersambung: simpan gerakan, coba lagi tick berikutnya
            pendX += ex
            pendY += ey
            return
        }

        fx += ex
        fy += ey

        val reach = (minOf(sw, sh) * 0.45f).coerceAtLeast(300f)
        val minX = maxOf(margin, mx - reach)
        val maxX = minOf(sw - margin, mx + reach)
        val minY = maxOf(margin, my - reach)
        val maxY = minOf(sh - margin, my + reach)
        val nx = fx.coerceIn(minX, maxX)
        val ny = fy.coerceIn(minY, maxY)
        if (nx != fx || ny != fy) {
            // Sampai batas: sisa gerakan dibawa ke jari berikutnya, bukan dibuang.
            pendX += fx - nx
            pendY += fy - ny
            fx = nx
            fy = ny
            Injector.touch(1, slot, fx, fy)
            recenterLocked(now)
            return
        }
        Injector.touch(1, slot, fx, fy)
    }

    private fun idleCheckLocked(now: Long) {
        if (!down || now - lastMoveAt < IDLE_MS) return
        val reach = (minOf(sw, sh) * 0.45f).coerceAtLeast(300f)
        if (hypot(fx - mx, fy - my) > reach * 0.3f) recenterLocked(now)
    }

    private fun recenterLocked(now: Long) {
        liftLocked()
        recenters++
        gapUntil = now + GAP_MS
    }

    private fun liftLocked() {
        if (down) {
            Injector.touch(2, slot, fx, fy)
            down = false
        }
    }

    private fun downLocked(): Boolean {
        val next = if (slot == SLOT_A) SLOT_B else SLOT_A     // id jari baru tiap kali dipasang
        if (!Injector.touch(0, next, mx, my)) return false
        slot = next
        fx = mx
        fy = my
        down = true
        return true
    }

    private companion object {
        const val SLOT_A = 20
        const val SLOT_B = 21
        const val TICK_NS = 4_000_000L      // 4 ms
        const val SMOOTH = 0.5f             // bagian sisa gerakan yang dikirim per tick
        const val GAP_MS = 10L              // jeda antara angkat dan pasang jari baru
        const val IDLE_MS = 150L            // mouse diam selama ini -> boleh ganti jari
        const val MAX_PEND = 3000f
    }
}
