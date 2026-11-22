package com.example.keymapper

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Menjalankan InjectMain sebagai shell (lewat Shizuku ATAU debug nirkabel) dan mengirim sentuhan.
object Injector {
    class Conn(val input: InputStream, val output: OutputStream, val err: InputStream?, val close: () -> Unit)

    @Volatile var ready = false
    @Volatile var mode = ""
    @Volatile var error = ""
    @Volatile var connecting = false
    var onChange: (() -> Unit)? = null
    private var conn: Conn? = null
    private val lock = Any()
    private data class TouchCommand(val action: Int, val id: Int, val x: Float = 0f, val y: Float = 0f)
    private val qLock = java.util.concurrent.locks.ReentrantLock()
    private val qCond: java.util.concurrent.locks.Condition = qLock.newCondition()
    private val touchQueue = ArrayDeque<TouchCommand>()
    private var lastAuto = 0L
    @Volatile private var lastProgress = 0L
    private val workerLock = Any()
    private var worker: Thread? = null

    // Satu thread pekerja yang TIDAK pernah keluar sendiri: saat antrean kosong ia menunggu di condition.
    // (Versi sebelumnya keluar saat antrean kosong, dan ada celah waktu di mana perintah baru masuk tepat
    // sebelum thread benar-benar mati sehingga antrean macet selamanya = sentuhan/klik berhenti total.)
    private fun ensureWorker() {
        synchronized(workerLock) {
            val existing = worker
            if (existing != null && existing.isAlive) return
            val t = Thread({ workerLoop() }, "VortexTouchQueue")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    private fun clearQueue() {
        qLock.lock()
        try { touchQueue.clear() } finally { qLock.unlock() }
    }

    private fun workerLoop() {
        while (true) {
            var command: TouchCommand? = null
            qLock.lock()
            try {
                while (touchQueue.isEmpty()) qCond.await()
                command = touchQueue.removeFirst()
            } catch (e: InterruptedException) {
                return
            } finally {
                qLock.unlock()
            }
            val cmd = command ?: continue
            try {
                val line = when (cmd.action) {
                    0 -> "D ${cmd.id} ${cmd.x} ${cmd.y}"
                    1 -> "M ${cmd.id} ${cmd.x} ${cmd.y}"
                    2 -> "U ${cmd.id}"
                    else -> "R"
                } + "\n"
                val wrote = synchronized(lock) {
                    val out = conn?.output
                    if (out == null || !ready) false
                    else {
                        out.write(line.toByteArray())
                        out.flush()
                        true
                    }
                }
                if (!wrote) clearQueue() else lastProgress = android.os.SystemClock.uptimeMillis()
            } catch (e: Exception) {
                ready = false
                changed()
                clearQueue()
            } catch (t: Throwable) {
                clearQueue()
            }
        }
    }

    private fun cmdLine(ctx: Context): String =
        "CLASSPATH=" + ctx.applicationInfo.sourceDir + " exec app_process /system/bin com.example.keymapper.InjectMain " + ctx.packageName

    private fun changed() { onChange?.invoke() }

    // backend: "Shizuku" atau "ADB". done(null) = sukses, selain itu pesan error (di thread latar).
    fun connect(ctx: Context, backend: String, done: ((String?) -> Unit)?) {
        if (connecting) { done?.invoke("Sedang menyambung..."); return }
        connecting = true
        changed()
        val app = ctx.applicationContext
        Thread {
            var err: String? = null
            try {
                stop()
                val c = if (backend == "Shizuku") ShizukuBridge.open(arrayOf("sh", "-c", cmdLine(app)))
                        else AdbBackend.open(app, cmdLine(app))
                conn = c
                val latch = CountDownLatch(1)
                val errText = StringBuilder()
                val es = c.err
                if (es != null) {
                    Thread {
                        try {
                            BufferedReader(InputStreamReader(es)).forEachLine {
                                if (errText.length < 400) errText.append(it).append(' ')
                            }
                        } catch (e: Exception) {}
                    }.start()
                }
                Thread {
                    try {
                        BufferedReader(InputStreamReader(c.input)).forEachLine { l ->
                            if (l == "READY") { mode = backend; ready = true; latch.countDown(); changed() }
                            else if (l.startsWith("ERR")) error = l
                        }
                    } catch (e: Exception) {}
                    ready = false
                    latch.countDown()
                    changed()
                }.start()
                latch.await(10, TimeUnit.SECONDS)
                if (!ready) {
                    err = if (errText.isNotEmpty()) errText.toString()
                          else if (error.isNotEmpty()) error else "Proses injeksi tidak mulai"
                }
            } catch (e: Throwable) {
                err = (e.cause ?: e).toString()
            }
            if (err != null) { error = err; stop() }
            connecting = false
            changed()
            done?.invoke(err)
        }.start()
    }

    // Sambung otomatis ke cara yang sedang aktif (Shizuku dulu, lalu debug nirkabel).
    fun autoStart(ctx: Context, done: ((String?) -> Unit)?) {
        if (ready) { done?.invoke(null); return }
        if (connecting) return
        val now = System.currentTimeMillis()
        if (done == null && now - lastAuto < 4000) return
        lastAuto = now
        when {
            ShizukuBridge.state(ctx) == 3 -> connect(ctx, "Shizuku", done)
            AdbBackend.canTry(ctx) -> connect(ctx, "ADB", done)
            else -> done?.invoke("Belum ada sambungan. Pilih Pairing Debug Nirkabel atau Shizuku.")
        }
    }

    // Mouse-look can produce many MOVE events per frame. Never write/flush the
    // Shizuku pipe on the UI thread: serialize injection on a worker and collapse
    // consecutive MOVE updates for the same pointer when the worker falls behind.
    fun touch(action: Int, id: Int, x: Float, y: Float): Boolean {
        if (!ready || conn?.output == null) return false
        qLock.lock()
        try {
            if (action == 1) {
                // Buang MOVE lama untuk jari yang sama setelah DOWN/UP terakhir; cukup posisi terbaru.
                val iter = touchQueue.descendingIterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    if (c.action == 3 || (c.id == id && c.action != 1)) break
                    if (c.id == id && c.action == 1) iter.remove()
                }
            }
            if (touchQueue.isEmpty()) lastProgress = android.os.SystemClock.uptimeMillis()
            touchQueue.addLast(TouchCommand(action, id, x, y))
            qCond.signal()
        } finally {
            qLock.unlock()
        }
        ensureWorker()
        return true
    }

    fun releaseAll() {
        if (!ready || conn?.output == null) return
        qLock.lock()
        try {
            // Lepas-semua tidak boleh antre di belakang tumpukan gerakan lama.
            touchQueue.clear()
            touchQueue.addLast(TouchCommand(3, 0))
            qCond.signal()
        } finally {
            qLock.unlock()
        }
        ensureWorker()
    }

    fun queued(): Int {
        qLock.lock()
        try { return touchQueue.size } finally { qLock.unlock() }
    }

    // Antrean berisi tetapi tidak ada kemajuan >0,8 detik = penulisan ke shell macet.
    fun stuck(): Boolean =
        ready && queued() > 0 && android.os.SystemClock.uptimeMillis() - lastProgress > 800

    fun restart(ctx: Context) {
        val backend = mode
        stop()
        if (backend == "Shizuku" || backend == "ADB") connect(ctx, backend, null) else autoStart(ctx, null)
    }

    fun stop() {
        ready = false
        clearQueue()
        val c = conn
        conn = null
        if (c != null) {
            // Perintah Q ditulis di thread terpisah: jika penulis lain sedang tersangkut, jangan ikut macet.
            val w = Thread {
                try {
                    synchronized(lock) {
                        c.output.write("Q\n".toByteArray())
                        c.output.flush()
                    }
                } catch (e: Exception) {}
            }
            w.isDaemon = true
            w.start()
            try { w.join(300) } catch (e: InterruptedException) {}
            try { c.close() } catch (e: Exception) {}
        }
    }
}
