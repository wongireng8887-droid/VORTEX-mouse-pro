package com.example.keymapper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuBridge {
    const val PKG = "moe.shizuku.privileged.api"
    private const val REQ = 4711

    var inject: IInject? = null
        private set
    var onChange: (() -> Unit)? = null
    private var binding = false
    private var registered = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            inject = if (service != null && service.pingBinder()) IInject.Stub.asInterface(service) else null
            binding = false
            onChange?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            inject = null; binding = false
            onChange?.invoke()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { tryBind(); onChange?.invoke() }
    private val deadListener = Shizuku.OnBinderDeadListener { inject = null; binding = false; onChange?.invoke() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) tryBind()
        onChange?.invoke()
    }

    fun init() {
        if (registered) return
        registered = true
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    private fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(PKG, 0); true
    } catch (e: Exception) { false }

    // 0 belum terpasang, 1 belum jalan, 2 belum diizinkan, 3 menyambung, 4 siap
    fun state(c: Context): Int {
        val alive = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!alive) return if (installed(c)) 1 else 0
        val granted = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
        if (!granted) return 2
        return if (inject != null) 4 else 3
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQ) } catch (e: Throwable) {}
    }

    fun tryBind() {
        if (inject != null || binding) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            binding = true
            val args = Shizuku.UserServiceArgs(ComponentName("com.example.keymapper", InjectService::class.java.name))
                .daemon(false)
                .processNameSuffix("inject")
                .debuggable(false)
                .version(6)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) { binding = false }
    }

    fun touch(action: Int, id: Int, x: Float, y: Float): Boolean =
        try { inject?.touch(action, id, x, y) ?: false } catch (e: Exception) { false }

    var lastTestOk = false

    // null = sukses; selain itu pesan error
    fun selfTest(): String? {
        val svc = inject ?: return "Belum tersambung ke layanan Shizuku"
        val r: String? = try {
            val a = svc.touch(0, 9, -20f, -20f)
            val b = svc.touch(2, 9, -20f, -20f)
            if (a && b) null else (svc.lastError() ?: "Injeksi gagal")
        } catch (e: Exception) { "Layanan Shizuku tidak merespons: " + e.message }
        lastTestOk = r == null
        return r
    }

    fun releaseAll() {
        try { inject?.releaseAll() } catch (e: Exception) {}
    }
}
