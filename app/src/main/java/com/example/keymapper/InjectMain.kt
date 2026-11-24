package com.example.keymapper

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

// Dijalankan sebagai shell lewat app_process. Perintah dari stdin:
//   D slot x y   (sentuh)    M slot x y   (geser)    U slot   (lepas)    R   (lepas semua)    Q   (keluar)
//   T slot x y   (mulai ketuk berulang)    S slot   (hentikan ketuk berulang)
object InjectMain {
    private var input: Any? = null
    private var inject: Method? = null
    private var setDisplayId: Method? = null
    private val pts = LinkedHashMap<Int, FloatArray>()
    private var downTime = 0L
    private var devId = 0
    private var lastErr = ""
    private val lock = Any()
    // ketuk berulang (mode Repeat) dijalankan di sini, bukan di thread utama aplikasi
    private val tapper = RepeatTapper(object : TapSink {
        override fun down(id: Int, x: Float, y: Float) { try { touch(0, id, x, y) } catch (e: Throwable) {} }
        override fun up(id: Int) { try { touch(2, id, 0f, 0f) } catch (e: Throwable) {} }
    })

    @JvmStatic
    fun main(args: Array<String>) {
        var err = ""
        // Beri aplikasi izin "akses penggunaan" lewat shell supaya logo V bisa muncul/hilang otomatis mengikuti game.
        if (args.isNotEmpty() && args[0].isNotEmpty()) {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/cmd", "appops", "set", args[0], "GET_USAGE_STATS", "allow")).waitFor()
                } catch (e: Throwable) {}
            }.start()
        }
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            input = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Throwable) { err = e.toString() }
        try { setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (e: Throwable) {}
        try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id)
                if (d != null && d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { devId = id; break }
            }
        } catch (e: Throwable) {}
        if (inject == null || input == null) {
            println("ERR init gagal: $err")
            System.out.flush()
            return
        }
        println("READY")
        System.out.flush()
        val r = BufferedReader(InputStreamReader(System.`in`))
        val pend = LinkedHashMap<Int, FloatArray>()
        // gerakan yang menumpuk digabung: hanya posisi terbaru, dikirim sebagai SATU event MOVE
        fun flushMoves() {
            if (pend.isEmpty()) return
            synchronized(lock) {
                var any = false
                for ((id, p) in pend) {
                    val cur = pts[id] ?: continue
                    cur[0] = p[0]; cur[1] = p[1]; any = true
                }
                pend.clear()
                if (any) send(MotionEvent.ACTION_MOVE)
            }
        }
        while (true) {
            val line = r.readLine() ?: break
            try {
                val p = line.trim().split(' ')
                if (p[0] == "M") {
                    pend[p[1].toInt()] = floatArrayOf(p[2].toFloat(), p[3].toFloat())
                } else {
                    flushMoves()
                    when (p[0]) {
                        "D" -> touch(0, p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                        "U" -> touch(2, p[1].toInt(), 0f, 0f)
                        "T" -> tapper.start(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                        "S" -> tapper.stop(p[1].toInt())
                        "R" -> { tapper.stopAll(); releaseAll() }
                        "Q" -> { tapper.stopAll(); releaseAll(); return }
                    }
                }
                if (!r.ready()) flushMoves()
            } catch (e: Throwable) {
                val m = "ERR " + e
                if (m != lastErr) { lastErr = m; println(m); System.out.flush() }
            }
        }
        flushMoves()
        tapper.stopAll()
        releaseAll()
    }

    private fun touch(action: Int, id: Int, x: Float, y: Float) {
        synchronized(lock) { touchLocked(action, id, x, y) }
    }

    private fun touchLocked(action: Int, id: Int, x: Float, y: Float) {
        when (action) {
            0 -> {
                if (pts.isEmpty()) downTime = SystemClock.uptimeMillis()
                pts[id] = floatArrayOf(x, y)
                val idx = pts.keys.indexOf(id)
                send(if (pts.size == 1) MotionEvent.ACTION_DOWN
                     else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            }
            1 -> {
                val p = pts[id] ?: return
                p[0] = x; p[1] = y
                send(MotionEvent.ACTION_MOVE)
            }
            else -> {
                if (!pts.containsKey(id)) return
                val idx = pts.keys.indexOf(id)
                send(if (pts.size == 1) MotionEvent.ACTION_UP
                     else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                pts.remove(id)
            }
        }
    }

    private fun releaseAll() {
        synchronized(lock) { releaseAllLocked() }
    }

    private fun releaseAllLocked() {
        try {
            for (id in pts.keys.toList()) touch(2, id, 0f, 0f)
            pts.clear()
        } catch (e: Throwable) {}
    }

    private fun send(action: Int) {
        val m = inject ?: return
        val target = input ?: return
        val n = pts.size
        val props = Array(n) { MotionEvent.PointerProperties() }
        val coords = Array(n) { MotionEvent.PointerCoords() }
        var i = 0
        for ((id, p) in pts) {
            props[i].id = id
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = p[0]
            coords[i].y = p[1]
            coords[i].pressure = 1f
            coords[i].size = 1f
            i++
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f, devId, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { setDisplayId?.invoke(ev, 0) } catch (e: Throwable) {}
        val ok = m.invoke(target, ev, 0)
        ev.recycle()
        if (ok is Boolean && !ok) throw IllegalStateException("injectInputEvent false")
    }
}
