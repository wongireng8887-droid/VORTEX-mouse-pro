package com.example.keymapper

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

// Jalan di proses terpisah dengan identitas shell (lewat Shizuku).
class InjectService : IInject.Stub() {
    private var input: Any? = null
    private var inject: Method? = null
    private var setDisplayId: Method? = null
    private val pts = LinkedHashMap<Int, FloatArray>()
    private var downTime = 0L
    private var devId = 0
    private var err = ""

    init {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            input = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Throwable) { err = "init: " + e }
        try {
            setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (e: Throwable) {}
        try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id)
                if (d != null && d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { devId = id; break }
            }
        } catch (e: Throwable) {}
    }

    override fun destroy() {
        releaseAll()
        System.exit(0)
    }

    override fun lastError(): String = err

    override fun touch(action: Int, id: Int, x: Float, y: Float): Boolean {
        return try {
            when (action) {
                0 -> {
                    if (pts.isEmpty()) downTime = SystemClock.uptimeMillis()
                    pts[id] = floatArrayOf(x, y)
                    val idx = pts.keys.indexOf(id)
                    send(if (pts.size == 1) MotionEvent.ACTION_DOWN
                         else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    true
                }
                1 -> {
                    val p = pts[id]
                    if (p == null) false else {
                        p[0] = x; p[1] = y
                        send(MotionEvent.ACTION_MOVE)
                        true
                    }
                }
                else -> {
                    if (!pts.containsKey(id)) false else {
                        val idx = pts.keys.indexOf(id)
                        send(if (pts.size == 1) MotionEvent.ACTION_UP
                             else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                        pts.remove(id)
                        true
                    }
                }
            }
        } catch (e: Throwable) {
            err = "touch: " + e
            pts.remove(id)
            false
        }
    }

    override fun releaseAll() {
        try {
            for (id in pts.keys.toList()) {
                val p = pts[id] ?: continue
                touch(2, id, p[0], p[1])
            }
            pts.clear()
        } catch (e: Throwable) {}
    }

    private fun send(action: Int) {
        val m = inject ?: throw IllegalStateException("injectInputEvent tidak ditemukan. " + err)
        val target = input ?: throw IllegalStateException("IInputManager tidak ditemukan. " + err)
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
        if (ok is Boolean && !ok) throw IllegalStateException("injectInputEvent mengembalikan false")
    }
}
