package com.example.keymapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

object PairHelper {
    private const val CH = "vortex_pair"
    private const val NOTIF = 77
    const val ACTION = "vortex.PAIR_CODE"

    @Volatile var status = ""
    var onChange: (() -> Unit)? = null
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private fun changed() { onChange?.invoke() }

    private fun nm(ctx: Context): NotificationManager {
        val n = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        n.createNotificationChannel(NotificationChannel(CH, "Pairing debug nirkabel", NotificationManager.IMPORTANCE_HIGH))
        return n
    }

    private fun post(ctx: Context, text: String, port: Int) {
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_game)
            .setContentTitle("Pairing debug nirkabel")
            .setContentText(text)
            .setOnlyAlertOnce(true)
        if (port > 0) {
            val ri = RemoteInput.Builder("code").setLabel("Kode pairing (6 angka)").build()
            val pi = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, PairReceiver::class.java).setAction(ACTION).putExtra("port", port),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val act = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_keyboard), "Masukkan kode", pi)
                .addRemoteInput(ri).build()
            b.addAction(act)
        }
        nm(ctx).notify(NOTIF, b.build())
    }

    fun start(ctx: Context) {
        stopDiscovery()
        val app = ctx.applicationContext
        status = "Mencari... buka Debugging nirkabel, pilih Pair device with pairing code."
        changed()
        val m = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                try {
                    m.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            status = "Port ditemukan. Tarik notifikasi, tap Masukkan kode."
                            post(app, "Tap Masukkan kode lalu ketik kode pairing", i.port)
                            changed()
                        }
                    })
                } catch (e: Exception) {}
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { status = "Gagal mencari ($e)"; changed() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        listener = l
        try { m.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
        catch (e: Exception) { status = "Gagal mencari: " + e.message; changed() }
    }

    private fun stopDiscovery() {
        val l = listener
        if (l != null) { try { nsd?.stopServiceDiscovery(l) } catch (e: Exception) {} }
        listener = null
    }

    fun doPair(ctx: Context, port: Int, code: String) {
        status = "Memasangkan..."
        post(ctx, "Memasangkan...", 0)
        changed()
        try {
            val ok = AdbBackend.manager(ctx).pair("127.0.0.1", port, code)
            if (!ok) throw IllegalStateException("kode salah atau port berubah")
            status = "Pairing berhasil. Menyambung..."
            changed()
            stopDiscovery()
            Injector.connect(ctx, "ADB") { err ->
                status = if (err == null) "Tersambung lewat debug nirkabel." else "Pairing OK, sambung gagal: $err"
                post(ctx, status, 0)
                changed()
            }
        } catch (e: Throwable) {
            status = "Pairing gagal: " + (e.message ?: e.toString()) + ". Ulangi Mulai Pairing."
            post(ctx, status, 0)
            changed()
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence("code")?.toString()?.trim() ?: return
        val port = i.getIntExtra("port", 0)
        val pending = goAsync()
        val app = c.applicationContext
        Thread {
            try { PairHelper.doPair(app, port, code) } finally { pending.finish() }
        }.start()
    }
}
