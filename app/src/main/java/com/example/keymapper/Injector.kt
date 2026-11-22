package com.example.keymapper

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
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
    private var lastAuto = 0L

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

    fun touch(action: Int, id: Int, x: Float, y: Float): Boolean {
        val o = conn?.output ?: return false
        if (!ready) return false
        val line = when (action) { 0 -> "D $id $x $y"; 1 -> "M $id $x $y"; else -> "U $id" } + "\n"
        return try {
            synchronized(lock) { o.write(line.toByteArray()); o.flush() }
            true
        } catch (e: Exception) {
            ready = false
            changed()
            false
        }
    }

    fun releaseAll() {
        val o = conn?.output ?: return
        try { synchronized(lock) { o.write("R\n".toByteArray()); o.flush() } } catch (e: Exception) {}
    }

    fun stop() {
        ready = false
        val c = conn
        conn = null
        if (c != null) {
            try { c.output.write("Q\n".toByteArray()); c.output.flush() } catch (e: Exception) {}
            try { c.close() } catch (e: Exception) {}
        }
    }
}
