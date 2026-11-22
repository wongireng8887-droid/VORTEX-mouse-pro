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
    private val queueLock = Any()
    private data class TouchCommand(val action: Int, val id: Int, val x: Float = 0f, val y: Float = 0f)
    private val touchQueue = ArrayDeque<TouchCommand>()
    private var drainingQueue = false
    private var lastAuto = 0L
    // Single long-lived worker so we never thrash threads under high mouse+key load.
    private val workerLock = Any()
    private var worker: Thread? = null
    private fun ensureWorker() {
        synchronized(workerLock) {
            val existing = worker
            if (existing != null && existing.isAlive) return
            val t = Thread({
                while (true) {
                    val command = synchronized(queueLock) {
                        if (touchQueue.isEmpty()) {
                            drainingQueue = false
                            null
                        } else touchQueue.removeFirst()
                    } ?: break
                    val output = conn?.output
                    if (output == null || !ready) {
                        synchronized(queueLock) {
                            touchQueue.clear()
                            drainingQueue = false
                        }
                        break
                    }
                    val line = when (command.action) {
                        0 -> "D ${command.id} ${command.x} ${command.y}"
                        1 -> "M ${command.id} ${command.x} ${command.y}"
                        2 -> "U ${command.id}"
                        else -> "R"
                    } + "\n"
                    val wrote = try {
                        synchronized(lock) {
                            val current = conn?.output
                            if (current == null || !ready) false
                            else {
                                current.write(line.toByteArray())
                                current.flush()
                                true
                            }
                        }
                    } catch (e: Exception) {
                        ready = false
                        changed()
                        false
                    }
                    if (!wrote) {
                        synchronized(queueLock) {
                            touchQueue.clear()
                            drainingQueue = false
                        }
                        break
                    }
                }
            }, "VortexTouchQueue")
            t.isDaemon = true
            worker = t
            t.start()
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
        synchronized(queueLock) {
            val command = TouchCommand(action, id, x, y)
            if (action == 1) {
                // Drop ALL queued MOVE updates for this pointer after the last
                // DOWN/UP barrier (not just one). Keeps only the latest position
                // so look stays smooth while other fingers (fire, etc.) are held.
                val pending = touchQueue.toList()
                val barrier = pending.indexOfLast { it.id == id && it.action != 1 }
                val compacted = pending.filterIndexed { index, cmd ->
                    !(cmd.id == id && cmd.action == 1 && index > barrier)
                }
                touchQueue.clear()
                touchQueue.addAll(compacted)
            }
            touchQueue.addLast(command)
            if (!drainingQueue) {
                drainingQueue = true
                ensureWorker()
            }
        }
        return true
    }

    fun releaseAll() {
        if (!ready || conn?.output == null) return
        synchronized(queueLock) {
            // A global release must not sit behind stale mouse-motion backlog.
            touchQueue.clear()
            touchQueue.addLast(TouchCommand(3, 0))
            if (!drainingQueue) {
                drainingQueue = true
                ensureWorker()
            }
        }
    }

    fun stop() {
        ready = false
        synchronized(queueLock) { touchQueue.clear() }
        val c = conn
        conn = null
        if (c != null) {
            try {
                synchronized(lock) {
                    c.output.write("Q\n".toByteArray())
                    c.output.flush()
                }
            } catch (e: Exception) {}
            try { c.close() } catch (e: Exception) {}
        }
    }
}
